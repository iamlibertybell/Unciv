package com.unciv.logic.automation.city

import com.unciv.logic.automation.Automation
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.city.CityConstructions
import com.unciv.logic.city.INonPerpetualConstruction
import com.unciv.logic.city.PerpetualConstruction
import com.unciv.logic.civilization.CityAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.BFS
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.Victory
import com.unciv.models.ruleset.unique.StateForConditionals
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import kotlin.math.max
import kotlin.math.sqrt

class ConstructionAutomation(val cityConstructions: CityConstructions){

    private val cityInfo = cityConstructions.city
    private val civInfo = cityInfo.civ

    private val buildableBuildings = hashMapOf<String, Boolean>()
    private val buildableUnits = hashMapOf<String, Boolean>()
    private val buildings = cityInfo.getRuleset().buildings.values.asSequence()

    private val nonWonders = buildings.filterNot { it.isAnyWonder() }
        .filterNot { buildableBuildings[it.name] == false } // if we already know that this building can't be built here then don't even consider it
    private val statBuildings = nonWonders.filter { !it.isEmpty() && Automation.allowAutomatedConstruction(civInfo, cityInfo, it) }
    private val wonders = buildings.filter { it.isAnyWonder() }

    private val units = cityInfo.getRuleset().units.values.asSequence()
        .filterNot { buildableUnits[it.name] == false } // if we already know that this unit can't be built here then don't even consider it

    private val civUnits = civInfo.units.getCivUnits()
    private val militaryUnits = civUnits.count { it.baseUnit.isMilitary() }
    private val workers = civUnits.count { it.cache.hasUniqueToBuildImprovements && it.isCivilian() }.toFloat()
    private val cities = civInfo.cities.size
    private val allTechsAreResearched = civInfo.gameInfo.ruleSet.technologies.values
        .all { civInfo.tech.isResearched(it.name) || !civInfo.tech.canBeResearched(it.name)}

    private val isAtWar = civInfo.isAtWar()
    private val buildingsForVictory = civInfo.gameInfo.getEnabledVictories().values
            .mapNotNull { civInfo.victoryManager.getNextMilestone(it.name) }
            .filter { it.type == MilestoneType.BuiltBuilding || it.type == MilestoneType.BuildingBuiltGlobally }
            .map { it.params[0] }

    private val spaceshipParts = civInfo.gameInfo.spaceResources


    private val averageProduction = civInfo.cities.map { it.cityStats.currentCityStats.production }.average()
    private val cityIsOverAverageProduction = cityInfo.cityStats.currentCityStats.production >= averageProduction

    private val relativeCostEffectiveness = ArrayList<ConstructionChoice>()

    private data class ConstructionChoice(val choice: String, var choiceModifier: Float, val remainingWork: Int)

    private fun addChoice(choices: ArrayList<ConstructionChoice>, choice: String, choiceModifier: Float) {
        choices.add(ConstructionChoice(choice, choiceModifier, cityConstructions.getRemainingWork(choice)))
    }

    private fun Sequence<INonPerpetualConstruction>.filterBuildable(): Sequence<INonPerpetualConstruction> {
        return this.filter {
            val cache = if (it is Building) buildableBuildings else buildableUnits
            if (cache[it.name] == null) {
                cache[it.name] = it.isBuildable(cityConstructions)
            }
            cache[it.name]!!
        }
    }


    fun chooseNextConstruction() {
        if (cityConstructions.getCurrentConstruction() !is PerpetualConstruction) return  // don't want to be stuck on these forever

        addFoodBuildingChoice()
        addProductionBuildingChoice()
        addGoldBuildingChoice()
        addScienceBuildingChoice()
        addHappinessBuildingChoice()
        addDefenceBuildingChoice()
        addUnitTrainingBuildingChoice()
        addCultureBuildingChoice()
        addOtherBuildingChoice()

        if (!cityInfo.isPuppet) {
            addSpaceshipPartChoice()
            addWondersChoice()
            addWorkerChoice()
            addWorkBoatChoice()
            addMilitaryUnitChoice()
        }

        val production = cityInfo.cityStats.currentCityStats.production

        val chosenConstruction: String =
            if (relativeCostEffectiveness.isEmpty()) { // choose one of the special constructions instead
                // add science!
                when {
                    PerpetualConstruction.science.isBuildable(cityConstructions) && !allTechsAreResearched -> PerpetualConstruction.science.name
                    PerpetualConstruction.gold.isBuildable(cityConstructions) -> PerpetualConstruction.gold.name
                    else -> PerpetualConstruction.idle.name
                }
            } else if (relativeCostEffectiveness.any { it.remainingWork < production * 30 }) {
                relativeCostEffectiveness.removeAll { it.remainingWork >= production * 30 }
                relativeCostEffectiveness.minByOrNull { it.remainingWork / it.choiceModifier }!!.choice
            }
            // it's possible that this is a new city and EVERYTHING is way expensive - ignore modifiers, go for the cheapest.
            // Nobody can plan 30 turns ahead, I don't care how cost-efficient you are.
            else relativeCostEffectiveness.minByOrNull { it.remainingWork }!!.choice

        civInfo.addNotification(
            "Work has started on [$chosenConstruction]",
            CityAction(cityInfo.location),
            NotificationCategory.Production,
            NotificationIcon.Construction
        )
        cityConstructions.currentConstructionFromQueue = chosenConstruction
    }

    private fun addMilitaryUnitChoice() {
        if (!isAtWar && !cityIsOverAverageProduction) return // don't make any military units here. Infrastructure first!
        if (!isAtWar && (civInfo.stats.statsForNextTurn.gold < 0 || militaryUnits > max(5, cities * 2))) return
        if (civInfo.gold < -50) return

        val militaryUnit = Automation.chooseMilitaryUnit(cityInfo, units) ?: return
        val unitsToCitiesRatio = cities.toFloat() / (militaryUnits + 1)
        // most buildings and civ units contribute the the civ's growth, military units are anti-growth
        var modifier = sqrt(unitsToCitiesRatio) / 2
        if (civInfo.wantsToFocusOn(Victory.Focus.Military) || isAtWar) modifier *= 2

        if (Automation.afraidOfBarbarians(civInfo)) modifier = 2f // military units are pro-growth if pressured by barbs
        if (!cityIsOverAverageProduction) modifier /= 5 // higher production cities will deal with this

        val civilianUnit = cityInfo.getCenterTile().civilianUnit
        if (civilianUnit != null && civilianUnit.hasUnique(UniqueType.FoundCity)
                && cityInfo.getCenterTile().getTilesInDistance(5).none { it.militaryUnit?.civ == civInfo })
            modifier = 5f // there's a settler just sitting here, doing nothing - BAD

        if (civInfo.playerType == PlayerType.Human) modifier /= 2 // Players prefer to make their own unit choices usually
        addChoice(relativeCostEffectiveness, militaryUnit, modifier)
    }

    private fun addWorkBoatChoice() {
        val buildableWorkboatUnits = units
            .filter {
                it.hasUnique(UniqueType.CreateWaterImprovements)
                    && Automation.allowAutomatedConstruction(civInfo, cityInfo, it)
            }.filterBuildable()
        val alreadyHasWorkBoat = buildableWorkboatUnits.any()
            && !cityInfo.getTiles().any {
                it.civilianUnit?.hasUnique(UniqueType.CreateWaterImprovements) == true
            }
        if (!alreadyHasWorkBoat) return


        val bfs = BFS(cityInfo.getCenterTile()) {
            (it.isWater || it.isCityCenter()) && it.isFriendlyTerritory(civInfo)
        }
        for (i in 1..10) bfs.nextStep()
        if (!bfs.getReachedTiles()
            .any { tile ->
                tile.hasViewableResource(civInfo) && tile.improvement == null && tile.getOwner() == civInfo
                        && tile.tileResource.getImprovements().any {
                    tile.improvementFunctions.canBuildImprovement(tile.ruleset.tileImprovements[it]!!, civInfo)
                }
            }
        ) return

        addChoice(
            relativeCostEffectiveness, buildableWorkboatUnits.minByOrNull { it.cost }!!.name,
            0.6f
        )
    }

    private fun addWorkerChoice() {
        val workerEquivalents = units
            .filter {
                it.hasUnique(UniqueType.BuildImprovements)
                        && Automation.allowAutomatedConstruction(civInfo, cityInfo, it)
            }.filterBuildable()
        if (workerEquivalents.none()) return // for mods with no worker units

        // For the first 3 cities, dedicate a worker, from then on only build another worker if you have 12 cities.
        val numberOfWorkersWeWant = if (cities < 4) cities else max(3, cities/3)

        if (workers < numberOfWorkersWeWant) {
            var modifier = numberOfWorkersWeWant / (workers + 0.1f) // The worse our worker to city ratio is, the more desperate we are
            if (!cityIsOverAverageProduction) modifier /= 5 // higher production cities will deal with this
            addChoice(relativeCostEffectiveness, workerEquivalents.minByOrNull { it.cost }!!.name, modifier)
        }
    }

    private fun addCultureBuildingChoice() {
        val cultureBuilding = statBuildings
            .filter { it.isStatRelated(Stat.Culture) }
            .filterBuildable()
            .minByOrNull { it.cost }
        if (cultureBuilding != null) {
            var modifier = 0.5f
            if (cityInfo.cityStats.currentCityStats.culture == 0f) // It won't grow if we don't help it
                modifier = 0.8f
            if (civInfo.wantsToFocusOn(Victory.Focus.Culture)) modifier = 1.6f
            addChoice(relativeCostEffectiveness, cultureBuilding.name, modifier)
        }
    }

    private fun addSpaceshipPartChoice() {
        if (!civInfo.hasUnique(UniqueType.EnablesConstructionOfSpaceshipParts)) return
        val spaceshipPart = (nonWonders + units).filter { it.name in spaceshipParts }.filterBuildable().firstOrNull()
        if (spaceshipPart != null) {
            val modifier = 2f
            addChoice(relativeCostEffectiveness, spaceshipPart.name, modifier)
        }
    }

    private fun addOtherBuildingChoice() {
        val otherBuilding = nonWonders
            .filter { Automation.allowAutomatedConstruction(civInfo, cityInfo, it) }
            .filterBuildable()
            .minByOrNull { it.cost }
        if (otherBuilding != null) {
            val modifier = 0.6f
            addChoice(relativeCostEffectiveness, otherBuilding.name, modifier)
        }
    }

    private fun getWonderPriority(wonder: Building): Float {
        // Only start building if we are the city that would complete it the soonest
        if (wonder.hasUnique(UniqueType.TriggersCulturalVictory)
                && cityInfo == civInfo.cities.minByOrNull {
                    it.cityConstructions.turnsToConstruction(wonder.name)
                }!!
        ) {
            return 10f
        }
        if (wonder.name in buildingsForVictory)
            return 5f
        if (civInfo.wantsToFocusOn(Victory.Focus.Culture)
                // TODO: Moddability
                && wonder.name in listOf("Sistine Chapel", "Eiffel Tower", "Cristo Redentor", "Neuschwanstein", "Sydney Opera House"))
            return 3f
        if (wonder.isStatRelated(Stat.Science)) {
            if (allTechsAreResearched) return .5f
            return if (civInfo.wantsToFocusOn(Victory.Focus.Science)) 1.5f
            else 1.3f
        }
        if (wonder.hasUnique(UniqueType.EnablesNuclearWeapons)) {
            return if (civInfo.wantsToFocusOn(Victory.Focus.Military)) 2f
            else 1.3f
        }
        if (wonder.isStatRelated(Stat.Happiness)) return 1.2f
        if (wonder.isStatRelated(Stat.Production)) return 1.1f
        return 1f
    }

    private fun addWondersChoice() {
        if (!wonders.any()) return

        val highestPriorityWonder = wonders
            .filter { Automation.allowAutomatedConstruction(civInfo, cityInfo, it) }
            .filterBuildable()
            .maxByOrNull { getWonderPriority(it as Building) }
            ?: return

        val citiesBuildingWonders = civInfo.cities
                .count { it.cityConstructions.isBuildingWonder() }

        var modifier = 2f * getWonderPriority(highestPriorityWonder as Building) / (citiesBuildingWonders + 1)
        if (!cityIsOverAverageProduction) modifier /= 5  // higher production cities will deal with this
        addChoice(relativeCostEffectiveness, highestPriorityWonder.name, modifier)
    }

    private fun addUnitTrainingBuildingChoice() {
        val unitTrainingBuilding = nonWonders
            .filter { it.hasUnique(UniqueType.UnitStartingExperience)
                    && Automation.allowAutomatedConstruction(civInfo, cityInfo, it)
            }
            .filterBuildable()
            .minByOrNull { it.cost }
        if (unitTrainingBuilding != null && (!civInfo.wantsToFocusOn(Victory.Focus.Culture) || isAtWar)) {
            var modifier = if (cityIsOverAverageProduction) 0.5f else 0.1f // You shouldn't be cranking out units anytime soon
            if (isAtWar) modifier *= 2
            if (civInfo.wantsToFocusOn(Victory.Focus.Military))
                modifier *= 1.3f
            addChoice(relativeCostEffectiveness, unitTrainingBuilding.name, modifier)
        }
    }

    private fun addDefenceBuildingChoice() {
        val defensiveBuilding = nonWonders
            .filter { it.cityStrength > 0
                    && Automation.allowAutomatedConstruction(civInfo, cityInfo, it)
            }
            .filterBuildable()
            .minByOrNull { it.cost }
        if (defensiveBuilding != null && (isAtWar || !civInfo.wantsToFocusOn(Victory.Focus.Culture))) {
            var modifier = 0.2f
            if (isAtWar) modifier = 0.5f

            // If this city is the closest city to another civ, that makes it a likely candidate for attack
            if (civInfo.getKnownCivs()
                        .mapNotNull { NextTurnAutomation.getClosestCities(civInfo, it) }
                        .any { it.city1 == cityInfo })
                modifier *= 1.5f

            addChoice(relativeCostEffectiveness, defensiveBuilding.name, modifier)
        }
    }

    private fun addHappinessBuildingChoice() {
        val happinessBuilding = nonWonders
            .filter { (it.isStatRelated(Stat.Happiness)
                    || it.hasUnique(UniqueType.RemoveAnnexUnhappiness))
                    && Automation.allowAutomatedConstruction(civInfo, cityInfo, it) }
            .filterBuildable()
            .minByOrNull { it.cost }
        if (happinessBuilding != null) {
            var modifier = 1f
            val civHappiness = civInfo.getHappiness()
            if (civHappiness > 5) modifier = 1 / 2f // less desperate
            if (civHappiness < 0) modifier = 3f // more desperate
            else if (happinessBuilding.hasUnique(UniqueType.RemoveAnnexUnhappiness)) modifier = 2f // building courthouse is always important
            addChoice(relativeCostEffectiveness, happinessBuilding.name, modifier)
        }
    }

    private fun addScienceBuildingChoice() {
        if (allTechsAreResearched) return
        val scienceBuilding = statBuildings
            .filter { it.isStatRelated(Stat.Science)
                    && Automation.allowAutomatedConstruction(civInfo, cityInfo, it) }
            .filterBuildable()
            .minByOrNull { it.cost }
        if (scienceBuilding != null) {
            var modifier = 1.1f
            if (civInfo.wantsToFocusOn(Victory.Focus.Science))
                modifier *= 1.4f
            addChoice(relativeCostEffectiveness, scienceBuilding.name, modifier)
        }
    }

    private fun addGoldBuildingChoice() {
        val goldBuilding = statBuildings.filter { it.isStatRelated(Stat.Gold) }
            .filterBuildable()
            .minByOrNull { it.cost }
        if (goldBuilding != null) {
            val modifier = if (civInfo.stats.statsForNextTurn.gold < 0) 3f else 1.2f
            addChoice(relativeCostEffectiveness, goldBuilding.name, modifier)
        }
    }

    private fun addProductionBuildingChoice() {
        val productionBuilding = statBuildings
            .filter { it.isStatRelated(Stat.Production) }
            .filterBuildable()
            .minByOrNull { it.cost }
        if (productionBuilding != null) {
            addChoice(relativeCostEffectiveness, productionBuilding.name, 1.5f)
        }
    }

    private fun addFoodBuildingChoice() {
        val conditionalState = StateForConditionals(civInfo, cityInfo)
        val foodBuilding = nonWonders
            .filter {
                (it.isStatRelated(Stat.Food)
                    || it.hasUnique(UniqueType.CarryOverFood, conditionalState)
                ) && Automation.allowAutomatedConstruction(civInfo, cityInfo, it)
            }.filterBuildable().minByOrNull { it.cost }
        if (foodBuilding != null) {
            var modifier = 1f
            if (cityInfo.population.population < 5) modifier = 1.3f
            addChoice(relativeCostEffectiveness, foodBuilding.name, modifier)
        }
    }
}
