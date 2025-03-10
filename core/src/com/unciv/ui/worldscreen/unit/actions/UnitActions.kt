package com.unciv.ui.worldscreen.unit.actions

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.automation.unit.WorkerAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Counter
import com.unciv.models.UncivSound
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.StateForConditionals
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.pickerscreens.ImprovementPickerScreen
import com.unciv.ui.pickerscreens.PromotionPickerScreen
import com.unciv.ui.popup.ConfirmPopup
import com.unciv.ui.popup.hasOpenPopups
import com.unciv.ui.utils.Fonts
import com.unciv.ui.worldscreen.WorldScreen
import com.unciv.ui.worldscreen.unit.UnitTable

object UnitActions {

    fun getUnitActions(unit: MapUnit, worldScreen: WorldScreen): List<UnitAction> {
        return if (unit.showAdditionalActions) getAdditionalActions(unit, worldScreen)
        else getNormalActions(unit, worldScreen)
    }

    private fun getNormalActions(unit: MapUnit, worldScreen: WorldScreen): List<UnitAction> {
        val tile = unit.getTile()
        val unitTable = worldScreen.bottomUnitTable
        val actionList = ArrayList<UnitAction>()

        if (unit.isMoving())
            actionList += UnitAction(UnitActionType.StopMovement) { unit.action = null }
        if (unit.isExploring())
            actionList += UnitAction(UnitActionType.StopExploration) { unit.action = null }
        if (unit.isAutomated())
            actionList += UnitAction(UnitActionType.StopAutomation) { unit.action = null }

        addSleepActions(actionList, unit, false)
        addFortifyActions(actionList, unit, false)

        addPromoteAction(unit, actionList)
        UnitActionsUpgrade.addUnitUpgradeAction(unit, actionList)
        addTransformAction(unit, actionList)
        UnitActionsPillage.addPillageAction(unit, actionList, worldScreen)
        addParadropAction(unit, actionList)
        addAirSweepAction(unit, actionList)
        addSetupAction(unit, actionList)
        addFoundCityAction(unit, actionList, tile)
        addBuildingImprovementsAction(unit, actionList, tile, worldScreen)
        addRepairAction(unit, actionList)
        addCreateWaterImprovements(unit, actionList)
        UnitActionsGreatPerson.addGreatPersonActions(unit, actionList, tile)
        UnitActionsReligion.addFoundReligionAction(unit, actionList)
        UnitActionsReligion.addEnhanceReligionAction(unit, actionList)
        actionList += getImprovementConstructionActions(unit, tile)
        UnitActionsReligion.addActionsWithLimitedUses(unit, actionList, tile)
        addExplorationActions(unit, actionList)
        addAutomateBuildingImprovementsAction(unit, actionList)
        addTriggerUniqueActions(unit, actionList)
        addAddInCapitalAction(unit, actionList, tile)

        addWaitAction(unit, actionList, worldScreen)

        addToggleActionsAction(unit, actionList, unitTable)

        return actionList
    }

    private fun getAdditionalActions(unit: MapUnit, worldScreen: WorldScreen): List<UnitAction> {
        val tile = unit.getTile()
        val unitTable = worldScreen.bottomUnitTable
        val actionList = ArrayList<UnitAction>()

        addSleepActions(actionList, unit, true)
        addFortifyActions(actionList, unit, true)

        addSwapAction(unit, actionList, worldScreen)
        addDisbandAction(actionList, unit, worldScreen)
        addGiftAction(unit, actionList, tile)


        addToggleActionsAction(unit, actionList, unitTable)

        return actionList
    }

    private fun addSwapAction(unit: MapUnit, actionList: ArrayList<UnitAction>, worldScreen: WorldScreen) {
        // Air units cannot swap
        if (unit.baseUnit.movesLikeAirUnits()) return
        // Disable unit swapping if multiple units are selected. It would make little sense.
        // In principle, the unit swapping mode /will/ function with multiselect: it will simply
        // only consider the first selected unit, and ignore the other selections. However, it does
        // have the visual bug that the tile overlays for the eligible swap locations are drawn for
        // /all/ selected units instead of only the first one. This could be fixed, but again,
        // swapping makes little sense for multiselect anyway.
        if (worldScreen.bottomUnitTable.selectedUnits.size > 1) return
        // Only show the swap action if there is at least one possible swap movement
        if (unit.movement.getUnitSwappableTiles().none()) return
        actionList += UnitAction(
            type = UnitActionType.SwapUnits,
            isCurrentAction = worldScreen.bottomUnitTable.selectedUnitIsSwapping,
            action = {
                worldScreen.bottomUnitTable.selectedUnitIsSwapping = !worldScreen.bottomUnitTable.selectedUnitIsSwapping
                worldScreen.shouldUpdate = true
            }
        )
    }

    private fun addDisbandAction(actionList: ArrayList<UnitAction>, unit: MapUnit, worldScreen: WorldScreen) {
        actionList += UnitAction(type = UnitActionType.DisbandUnit, action = {
            if (!worldScreen.hasOpenPopups()) {
                val disbandText = if (unit.currentTile.getOwner() == unit.civ)
                    "Disband this unit for [${unit.baseUnit.getDisbandGold(unit.civ)}] gold?".tr()
                else "Do you really want to disband this unit?".tr()
                ConfirmPopup(UncivGame.Current.worldScreen!!, disbandText, "Disband unit") {
                    unit.disband()
                    worldScreen.shouldUpdate = true
                    if (UncivGame.Current.settings.autoUnitCycle) worldScreen.switchToNextUnit()
                }.open()
            }
        }.takeIf { unit.currentMovement > 0 })
    }

    private fun addCreateWaterImprovements(unit: MapUnit, actionList: ArrayList<UnitAction>) {
        val waterImprovementAction = getWaterImprovementAction(unit)
        if (waterImprovementAction != null) actionList += waterImprovementAction
    }

    fun getWaterImprovementAction(unit: MapUnit): UnitAction? {
        val tile = unit.currentTile
        if (!tile.isWater || !unit.hasUnique(UniqueType.CreateWaterImprovements) || tile.resource == null) return null

        val improvementName = tile.tileResource.getImprovingImprovement(tile, unit.civ) ?: return null
        val improvement = tile.ruleset.tileImprovements[improvementName] ?: return null
        if (!tile.improvementFunctions.canBuildImprovement(improvement, unit.civ)) return null

        return UnitAction(UnitActionType.Create, "Create [$improvementName]",
            action = {
                tile.changeImprovement(improvementName)
                val city = tile.getCity()
                if (city != null) {
                    city.cityStats.update()
                    city.civ.cache.updateCivResources()
                }
                unit.destroy()
            }.takeIf { unit.currentMovement > 0 })
    }


    private fun addFoundCityAction(unit: MapUnit, actionList: ArrayList<UnitAction>, tile: Tile) {
        val getFoundCityAction = getFoundCityAction(unit, tile)
        if (getFoundCityAction != null) actionList += getFoundCityAction
    }

    /** Produce a [UnitAction] for founding a city.
     * @param unit The unit to do the founding.
     * @param tile The tile to found a city on.
     * @return null if impossible (the unit lacks the ability to found),
     * or else a [UnitAction] 'defining' the founding.
     * The [action][UnitAction.action] field will be null if the action cannot be done here and now
     * (no movement left, too close to another city).
      */
    fun getFoundCityAction(unit: MapUnit, tile: Tile): UnitAction? {
        if (!unit.hasUnique(UniqueType.FoundCity)
                || tile.isWater || tile.isImpassible()) return null
        // Spain should still be able to build Conquistadors in a one city challenge - but can't settle them
        if (unit.civ.isOneCityChallenger() && unit.civ.hasEverOwnedOriginalCapital == true) return null

        if (unit.currentMovement <= 0 || !tile.canBeSettled())
            return UnitAction(UnitActionType.FoundCity, action = null)

        val foundAction = {
            UncivGame.Current.settings.addCompletedTutorialTask("Found city")
            unit.civ.addCity(tile.position)
            if (tile.ruleset.tileImprovements.containsKey("City center"))
                tile.changeImprovement("City center")
            tile.removeRoad()
            unit.destroy()
            UncivGame.Current.worldScreen!!.shouldUpdate = true
        }

        if (unit.civ.playerType == PlayerType.AI)
            return UnitAction(UnitActionType.FoundCity, action = foundAction)

        return UnitAction(
                type = UnitActionType.FoundCity,
                uncivSound = UncivSound.Chimes,
                action = {
                    // check if we would be breaking a promise
                    val leaders = testPromiseNotToSettle(unit.civ, tile)
                    if (leaders == null)
                        foundAction()
                    else {
                        // ask if we would be breaking a promise
                        val text = "Do you want to break your promise to [$leaders]?"
                        ConfirmPopup(UncivGame.Current.worldScreen!!, text, "Break promise", action = foundAction).open(force = true)
                    }
                }
            )
    }

    /**
     * Checks whether a civ founding a city on a certain tile would break a promise.
     * @param civInfo The civilization trying to found a city
     * @param tile The tile where the new city would go
     * @return null if no promises broken, else a String listing the leader(s) we would p* off.
     */
    private fun testPromiseNotToSettle(civInfo: Civilization, tile: Tile): String? {
        val brokenPromises = HashSet<String>()
        for (otherCiv in civInfo.getKnownCivs().filter { it.isMajorCiv() && !civInfo.isAtWarWith(it) }) {
            val diplomacyManager = otherCiv.getDiplomacyManager(civInfo)
            if (diplomacyManager.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs)) {
                val citiesWithin6Tiles = otherCiv.cities
                    .filter { it.getCenterTile().aerialDistanceTo(tile) <= 6 }
                    .filter { otherCiv.hasExplored(it.getCenterTile()) }
                if (citiesWithin6Tiles.isNotEmpty()) brokenPromises += otherCiv.getLeaderDisplayName()
            }
        }
        return if(brokenPromises.isEmpty()) null else brokenPromises.joinToString(", ")
    }

    private fun addPromoteAction(unit: MapUnit, actionList: ArrayList<UnitAction>) {
        if (unit.isCivilian() || !unit.promotions.canBePromoted()) return
        // promotion does not consume movement points, but is not allowed if a unit has exhausted its movement or has attacked
        actionList += UnitAction(UnitActionType.Promote,
            action = {
                UncivGame.Current.pushScreen(PromotionPickerScreen(unit))
            }.takeIf { unit.currentMovement > 0 && unit.attacksThisTurn == 0 })
    }

    private fun addSetupAction(unit: MapUnit, actionList: ArrayList<UnitAction>) {
        if (!unit.hasUnique(UniqueType.MustSetUp) || unit.isEmbarked()) return
        val isSetUp = unit.isSetUpForSiege()
        actionList += UnitAction(UnitActionType.SetUp,
                isCurrentAction = isSetUp,
                action = {
                    unit.action = UnitActionType.SetUp.value
                    unit.useMovementPoints(1f)
                }.takeIf { unit.currentMovement > 0 && !isSetUp })
    }

    private fun addParadropAction(unit: MapUnit, actionList: ArrayList<UnitAction>) {
        val paradropUniques =
            unit.getMatchingUniques(UniqueType.MayParadrop)
        if (!paradropUniques.any() || unit.isEmbarked()) return
        unit.cache.paradropRange = paradropUniques.maxOfOrNull { it.params[0] }!!.toInt()
        actionList += UnitAction(UnitActionType.Paradrop,
            isCurrentAction = unit.isPreparingParadrop(),
            action = {
                if (unit.isPreparingParadrop()) unit.action = null
                else unit.action = UnitActionType.Paradrop.value
            }.takeIf {
                unit.currentMovement == unit.getMaxMovement().toFloat() &&
                        unit.currentTile.isFriendlyTerritory(unit.civ) &&
                        !unit.isEmbarked()
            })
    }

    private fun addAirSweepAction(unit: MapUnit, actionList: ArrayList<UnitAction>) {
        val airsweepUniques =
            unit.getMatchingUniques(UniqueType.CanAirsweep)
        if (!airsweepUniques.any()) return
        actionList += UnitAction(UnitActionType.AirSweep,
            isCurrentAction = unit.isPreparingAirSweep(),
            action = {
                if (unit.isPreparingAirSweep()) unit.action = null
                else unit.action = UnitActionType.AirSweep.value
            }.takeIf {
                unit.canAttack()
            }
        )
    }


    private fun addExplorationActions(unit: MapUnit, actionList: ArrayList<UnitAction>) {
        if (unit.baseUnit.movesLikeAirUnits()) return
        if (unit.isExploring()) return
        actionList += UnitAction(UnitActionType.Explore) {
            unit.action = UnitActionType.Explore.value
            if (unit.currentMovement > 0) UnitAutomation.automatedExplore(unit)
        }
    }

    private fun addTransformAction(
        unit: MapUnit,
        actionList: ArrayList<UnitAction>
    ) {
        val upgradeAction = getTransformAction(unit)
        if (upgradeAction != null) actionList += upgradeAction
    }

    /**  */
    private fun getTransformAction(
        unit: MapUnit
    ): ArrayList<UnitAction>? {
        if (!unit.baseUnit().hasUnique(UniqueType.CanTransform)) return null // can't upgrade to anything
        val unitTile = unit.getTile()
        val civInfo = unit.civ
        val transformList = ArrayList<UnitAction>()
        for (unique in unit.baseUnit().getMatchingUniques(UniqueType.CanTransform,
            StateForConditionals(unit = unit, civInfo = civInfo, tile = unitTile))) {
            val upgradedUnit = civInfo.getEquivalentUnit(unique.params[0])
            // don't show if haven't researched/is obsolete
            if (!unit.upgrade.canUpgrade(unitToUpgradeTo = upgradedUnit)) continue

            // Check _new_ resource requirements
            // Using Counter to aggregate is a bit exaggerated, but - respect the mad modder.
            val resourceRequirementsDelta = Counter<String>()
            for ((resource, amount) in unit.baseUnit().getResourceRequirements())
                resourceRequirementsDelta.add(resource, -amount)
            for ((resource, amount) in upgradedUnit.getResourceRequirements())
                resourceRequirementsDelta.add(resource, amount)
            val newResourceRequirementsString = resourceRequirementsDelta.entries
                .filter { it.value > 0 }
                .joinToString { "${it.value} {${it.key}}".tr() }

            val title = if (newResourceRequirementsString.isEmpty())
                "Transform to [${upgradedUnit.name}]"
            else "Transform to [${upgradedUnit.name}]\n([$newResourceRequirementsString])"

            transformList.add(UnitAction(UnitActionType.Transform,
                title = title,
                action = {
                    unit.destroy()
                    val newUnit = civInfo.units.placeUnitNearTile(unitTile.position, upgradedUnit.name)

                    /** We were UNABLE to place the new unit, which means that the unit failed to upgrade!
                     * The only known cause of this currently is "land units upgrading to water units" which fail to be placed.
                     */
                    if (newUnit == null) {
                        val resurrectedUnit = civInfo.units.placeUnitNearTile(unitTile.position, unit.name)!!
                        unit.copyStatisticsTo(resurrectedUnit)
                    } else { // Managed to upgrade
                        unit.copyStatisticsTo(newUnit)
                        newUnit.currentMovement = 0f
                    }
                }.takeIf {
                    unit.currentMovement > 0
                            && !unit.isEmbarked()
                            && unit.upgrade.canUpgrade(unitToUpgradeTo = upgradedUnit)
                }
            ) )
        }
        return transformList
    }

    private fun addBuildingImprovementsAction(
        unit: MapUnit,
        actionList: ArrayList<UnitAction>,
        tile: Tile,
        worldScreen: WorldScreen
    ) {
        if (!unit.cache.hasUniqueToBuildImprovements) return
        if (unit.isEmbarked()) return

        val couldConstruct = unit.currentMovement > 0
            && !tile.isCityCenter()
            && unit.civ.gameInfo.ruleSet.tileImprovements.values.any {
                ImprovementPickerScreen.canReport(tile.improvementFunctions.getImprovementBuildingProblems(it, unit.civ).toSet())
                && unit.canBuildImprovement(it)
            }

        actionList += UnitAction(UnitActionType.ConstructImprovement,
            isCurrentAction = unit.currentTile.hasImprovementInProgress(),
            action = {
                worldScreen.game.pushScreen(ImprovementPickerScreen(tile, unit) { worldScreen.switchToNextUnit() })
            }.takeIf { couldConstruct }
        )
    }

    private fun getRepairTurns(unit: MapUnit): Int {
        val tile = unit.currentTile
        if (!tile.isPillaged()) return 0
        if (tile.improvementInProgress == Constants.repair) return tile.turnsToImprovement
        var repairTurns = tile.ruleset.tileImprovements[Constants.repair]!!.getTurnsToBuild(unit.civ, unit)

        val pillagedImprovement = tile.getImprovementToRepair()!!
        val turnsToBuild = pillagedImprovement.getTurnsToBuild(unit.civ, unit)
        // cap repair to number of turns to build original improvement
        if (turnsToBuild < repairTurns) repairTurns = turnsToBuild
        return repairTurns
    }

    private fun addRepairAction(unit: MapUnit, actionList: ArrayList<UnitAction>) {
        if (unit.currentTile.ruleset.tileImprovements[Constants.repair] == null) return
        if (!unit.cache.hasUniqueToBuildImprovements) return
        if (unit.isEmbarked()) return
        val tile = unit.getTile()
        if (tile.isCityCenter()) return
        if (!tile.isPillaged()) return

        val couldConstruct = unit.currentMovement > 0
                && !tile.isCityCenter() && tile.improvementInProgress != Constants.repair

        val turnsToBuild = getRepairTurns(unit)

        actionList += UnitAction(UnitActionType.Repair,
            title = "${UnitActionType.Repair} [${unit.currentTile.getImprovementToRepair()!!.name}] - [${turnsToBuild}${Fonts.turn}]",
            action = getRepairAction(unit).takeIf { couldConstruct }
        )
    }

    fun getRepairAction(unit: MapUnit): () -> Unit {
        return {
            val tile = unit.currentTile
            tile.turnsToImprovement = getRepairTurns(unit)
            tile.improvementInProgress = Constants.repair
        }
    }

    private fun addAutomateBuildingImprovementsAction(unit: MapUnit, actionList: ArrayList<UnitAction>) {
        if (!unit.cache.hasUniqueToBuildImprovements) return
        if (unit.isAutomated()) return

        actionList += UnitAction(UnitActionType.Automate,
            isCurrentAction = unit.isAutomated(),
            action = {
                unit.action = UnitActionType.Automate.value
                WorkerAutomation.automateWorkerAction(unit)
            }.takeIf { unit.currentMovement > 0 }
        )
    }

    fun getAddInCapitalAction(unit: MapUnit, tile: Tile): UnitAction {
        return UnitAction(UnitActionType.AddInCapital,
            title = "Add to [${unit.getMatchingUniques(UniqueType.AddInCapital).first().params[0]}]",
            action = {
                unit.civ.victoryManager.currentsSpaceshipParts.add(unit.name, 1)
                unit.destroy()
            }.takeIf { tile.isCityCenter() && tile.getCity()!!.isCapital() && tile.getCity()!!.civ == unit.civ }
        )
    }

    private fun addAddInCapitalAction(unit: MapUnit, actionList: ArrayList<UnitAction>, tile: Tile) {
        if (!unit.hasUnique(UniqueType.AddInCapital)) return

        actionList += getAddInCapitalAction(unit, tile)
    }

    fun getImprovementConstructionActions(unit: MapUnit, tile: Tile): ArrayList<UnitAction> {
        val finalActions = ArrayList<UnitAction>()
        val uniquesToCheck = unit.getMatchingUniques(UniqueType.ConstructImprovementConsumingUnit)
        val civResources = unit.civ.getCivResourcesByName()

        for (unique in uniquesToCheck) {
            val improvementName = unique.params[0]
            val improvement = tile.ruleset.tileImprovements[improvementName]
                ?: continue

            val resourcesAvailable = improvement.uniqueObjects.none {
                it.isOfType(UniqueType.ConsumesResources) &&
                        (civResources[unique.params[1]] ?: 0) < unique.params[0].toInt()
            }

            finalActions += UnitAction(UnitActionType.Create,
                title = "Create [$improvementName]",
                action = {
                    val unitTile = unit.getTile()
                    unitTile.improvementFunctions.removeCreatesOneImprovementMarker()
                    unitTile.changeImprovement(improvementName)
                    unitTile.stopWorkingOnImprovement()
                    improvement.handleImprovementCompletion(unit)
                    unit.consume()
                }.takeIf {
                    resourcesAvailable
                    && unit.currentMovement > 0f
                    && tile.improvementFunctions.canBuildImprovement(improvement, unit.civ)
                    // Next test is to prevent interfering with UniqueType.CreatesOneImprovement -
                    // not pretty, but users *can* remove the building from the city queue an thus clear this:
                    && !tile.isMarkedForCreatesOneImprovement()
                    && !tile.isImpassible() // Not 100% sure that this check is necessary...
                })
        }
        return finalActions
    }

    fun takeOverTilesAround(unit: MapUnit) {
        // This method should only be called for a citadel - therefore one of the neighbour tile
        // must belong to unit's civ, so minByOrNull in the nearestCity formula should be never `null`.
        // That is, unless a mod does not specify the proper unique - then fallbackNearestCity will take over.

        fun priority(tile: Tile): Int { // helper calculates priority (lower is better): distance plus razing malus
            val city = tile.getCity()!!       // !! assertion is guaranteed by the outer filter selector.
            return city.getCenterTile().aerialDistanceTo(tile) +
                    (if (city.isBeingRazed) 5 else 0)
        }
        fun fallbackNearestCity(unit: MapUnit) =
            unit.civ.cities.minByOrNull {
               it.getCenterTile().aerialDistanceTo(unit.currentTile) +
                   (if (it.isBeingRazed) 5 else 0)
            }!!

        // In the rare case more than one city owns tiles neighboring the citadel
        // this will prioritize the nearest one not being razed
        val nearestCity = unit.currentTile.neighbors
            .filter { it.getOwner() == unit.civ }
            .minByOrNull { priority(it) }?.getCity()
            ?: fallbackNearestCity(unit)

        // capture all tiles which do not belong to unit's civ and are not enemy cities
        // we use getTilesInDistance here, not neighbours to include the current tile as well
        val tilesToTakeOver = unit.currentTile.getTilesInDistance(1)
                .filter { !it.isCityCenter() && it.getOwner() != unit.civ }

        val civsToNotify = mutableSetOf<Civilization>()
        for (tile in tilesToTakeOver) {
            val otherCiv = tile.getOwner()
            if (otherCiv != null) {
                // decrease relations for -10 pt/tile
                if (!otherCiv.knows(unit.civ)) otherCiv.diplomacyFunctions.makeCivilizationsMeet(unit.civ)
                otherCiv.getDiplomacyManager(unit.civ).addModifier(DiplomaticModifiers.StealingTerritory, -10f)
                civsToNotify.add(otherCiv)
            }
            nearestCity.expansion.takeOwnership(tile)
        }

        for (otherCiv in civsToNotify)
            otherCiv.addNotification("Your territory has been stolen by [${unit.civ}]!",
                unit.currentTile.position, NotificationCategory.Cities, unit.civ.civName, NotificationIcon.War)
    }

    private fun addFortifyActions(actionList: ArrayList<UnitAction>, unit: MapUnit, showingAdditionalActions: Boolean) {
        if (unit.isFortified() && !showingAdditionalActions) {
            actionList += UnitAction(
                type = if (unit.isActionUntilHealed())
                    UnitActionType.FortifyUntilHealed else
                    UnitActionType.Fortify,
                isCurrentAction = true,
                title = "${"Fortification".tr()} ${unit.getFortificationTurns() * 20}%"
            )
            return
        }

        if (!unit.canFortify()) return
        if (unit.currentMovement == 0f) return

        val isFortified = unit.isFortified()
        val isDamaged = unit.health < 100

        if (isDamaged && !showingAdditionalActions && unit.rankTileForHealing(unit.currentTile) != 0)
            actionList += UnitAction(UnitActionType.FortifyUntilHealed,
                action = { unit.fortifyUntilHealed() }.takeIf { !unit.isFortifyingUntilHealed() })
        else if (isDamaged || !showingAdditionalActions)
            actionList += UnitAction(UnitActionType.Fortify,
                action = { unit.fortify() }.takeIf { !isFortified })
    }

    private fun addSleepActions(actionList: ArrayList<UnitAction>, unit: MapUnit, showingAdditionalActions: Boolean) {
        if (unit.isFortified() || unit.canFortify() || unit.currentMovement == 0f) return
        // If this unit is working on an improvement, it cannot sleep
        if (unit.currentTile.hasImprovementInProgress()
            && unit.canBuildImprovement(unit.currentTile.getTileImprovementInProgress()!!)) return
        val isSleeping = unit.isSleeping()
        val isDamaged = unit.health < 100

        if (isDamaged && !showingAdditionalActions) {
            actionList += UnitAction(UnitActionType.SleepUntilHealed,
                action = { unit.action = UnitActionType.SleepUntilHealed.value }
                    .takeIf { !unit.isSleepingUntilHealed() }
            )
        } else if (isDamaged || !showingAdditionalActions) {
            actionList += UnitAction(UnitActionType.Sleep,
                action = { unit.action = UnitActionType.Sleep.value }.takeIf { !isSleeping }
            )
        }
    }

    fun canPillage(unit: MapUnit, tile: Tile): Boolean {
        if (unit.isTransported) return false
        if (!tile.canPillageTile()) return false
        val tileOwner = tile.getOwner()
        // Can't pillage friendly tiles, just like you can't attack them - it's an 'act of war' thing
        return tileOwner == null || unit.civ.isAtWarWith(tileOwner)
    }

    private fun addGiftAction(unit: MapUnit, actionList: ArrayList<UnitAction>, tile: Tile) {
        val getGiftAction = getGiftAction(unit, tile)
        if (getGiftAction != null) actionList += getGiftAction
    }

    fun getGiftAction(unit: MapUnit, tile: Tile): UnitAction? {
        val recipient = tile.getOwner()
        // We need to be in another civs territory.
        if (recipient == null || recipient.isCurrentPlayer()) return null

        if (recipient.isCityState()) {
            if (recipient.isAtWarWith(unit.civ)) return null // No gifts to enemy CS
            // City States only take military units (and units specifically allowed by uniques)
            if (!unit.isMilitary()
                && unit.getMatchingUniques(UniqueType.GainInfluenceWithUnitGiftToCityState, checkCivInfoUniques = true)
                    .none { unit.matchesFilter(it.params[1]) }
            ) return null
        }
        // If gifting to major civ they need to be friendly
        else if (!tile.isFriendlyTerritory(unit.civ)) return null

        if (unit.currentMovement <= 0)
            return UnitAction(UnitActionType.GiftUnit, action = null)

        val giftAction = {
            if (recipient.isCityState()) {
                for (unique in unit.getMatchingUniques(UniqueType.GainInfluenceWithUnitGiftToCityState, checkCivInfoUniques = true)) {
                    if (unit.matchesFilter(unique.params[1])) {
                        recipient.getDiplomacyManager(unit.civ)
                            .addInfluence(unique.params[0].toFloat() - 5f)
                        break
                    }
                }

                recipient.getDiplomacyManager(unit.civ).addInfluence(5f)
            } else recipient.getDiplomacyManager(unit.civ)
                .addModifier(DiplomaticModifiers.GaveUsUnits, 5f)

            if (recipient.isCityState() && unit.isGreatPerson())
                unit.destroy()  // City states don't get GPs
            else
                unit.gift(recipient)
            UncivGame.Current.worldScreen!!.shouldUpdate = true
        }

        return UnitAction(UnitActionType.GiftUnit, action = giftAction)
    }

    private fun addTriggerUniqueActions(unit: MapUnit, actionList: ArrayList<UnitAction>){
        for (unique in unit.getUniques()) {
            if (!unique.conditionals.any { it.type == UniqueType.ConditionalConsumeUnit }) continue
            val unitAction = UnitAction(type = UnitActionType.TriggerUnique, unique.text){
                UniqueTriggerActivation.triggerCivwideUnique(unique, unit.civ)
                unit.consume()
            }
            actionList += unitAction
        }
    }

    private fun addWaitAction(unit: MapUnit, actionList: ArrayList<UnitAction>, worldScreen: WorldScreen) {
        actionList += UnitAction(
            type = UnitActionType.Wait,
            action = {
                unit.due = false
                worldScreen.switchToNextUnit()
            }
        )
    }

    private fun addToggleActionsAction(unit: MapUnit, actionList: ArrayList<UnitAction>, unitTable: UnitTable) {
        actionList += UnitAction(
            type = if (unit.showAdditionalActions) UnitActionType.HideAdditionalActions
            else UnitActionType.ShowAdditionalActions,
            action = {
                unit.showAdditionalActions = !unit.showAdditionalActions
                unitTable.update()
            }
        )
    }
}
