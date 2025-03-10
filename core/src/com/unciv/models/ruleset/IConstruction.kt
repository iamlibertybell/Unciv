package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.unique.IHasUniques
import com.unciv.models.ruleset.unique.StateForConditionals
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.INamed
import com.unciv.models.stats.Stat
import com.unciv.ui.utils.Fonts
import com.unciv.ui.utils.extensions.toPercent
import kotlin.math.pow
import kotlin.math.roundToInt

interface IConstruction : INamed {
    fun isBuildable(cityConstructions: CityConstructions): Boolean
    fun shouldBeDisplayed(cityConstructions: CityConstructions): Boolean
    fun getResourceRequirements(): HashMap<String,Int>
    fun requiresResource(resource: String): Boolean
}

interface INonPerpetualConstruction : IConstruction, INamed, IHasUniques {
    var cost: Int
    val hurryCostModifier: Int
    var requiredTech: String?

    fun getProductionCost(civInfo: Civilization): Int
    fun getStatBuyCost(city: City, stat: Stat): Int?
    fun getRejectionReasons(cityConstructions: CityConstructions): Sequence<RejectionReason>
    fun postBuildEvent(cityConstructions: CityConstructions, boughtWith: Stat? = null): Boolean  // Yes I'm hilarious.

    /** Only checks if it has the unique to be bought with this stat, not whether it is purchasable at all */
    fun canBePurchasedWithStat(city: City?, stat: Stat): Boolean {
        if (stat == Stat.Production || stat == Stat.Happiness) return false
        if (hasUnique(UniqueType.CannotBePurchased)) return false
        if (stat == Stat.Gold) return !hasUnique(UniqueType.Unbuildable)
        // Can be purchased with [Stat] [cityFilter]
        if (city != null && getMatchingUniques(UniqueType.CanBePurchasedWithStat)
            .any { it.params[0] == stat.name && city.matchesFilter(it.params[1]) }
        ) return true
        // Can be purchased for [amount] [Stat] [cityFilter]
        if (city != null && getMatchingUniques(UniqueType.CanBePurchasedForAmountStat)
            .any { it.params[1] == stat.name && city.matchesFilter(it.params[2]) }
        ) return true
        return false
    }

    /** Checks if the construction should be purchasable, not whether it can be bought with a stat at all */
    fun isPurchasable(cityConstructions: CityConstructions): Boolean {
        val rejectionReasons = getRejectionReasons(cityConstructions)
        return rejectionReasons.all { it.type == RejectionReasonType.Unbuildable }
    }

    fun canBePurchasedWithAnyStat(city: City): Boolean {
        return Stat.values().any { canBePurchasedWithStat(city, it) }
    }

    fun getBaseGoldCost(civInfo: Civilization): Double {
        // https://forums.civfanatics.com/threads/rush-buying-formula.393892/
        return (30.0 * getProductionCost(civInfo)).pow(0.75) * hurryCostModifier.toPercent()
    }

    fun getBaseBuyCost(city: City, stat: Stat): Int? {
        if (stat == Stat.Gold) return getBaseGoldCost(city.civ).toInt()

        val conditionalState = StateForConditionals(civInfo = city.civ, city = city)

        // Can be purchased for [amount] [Stat] [cityFilter]
        val lowestCostUnique = getMatchingUniques(UniqueType.CanBePurchasedForAmountStat, conditionalState)
            .filter { it.params[1] == stat.name && city.matchesFilter(it.params[2]) }
            .minByOrNull { it.params[0].toInt() }
        if (lowestCostUnique != null) return lowestCostUnique.params[0].toInt()

        // Can be purchased with [Stat] [cityFilter]
        if (getMatchingUniques(UniqueType.CanBePurchasedWithStat, conditionalState)
            .any { it.params[0] == stat.name && city.matchesFilter(it.params[1]) }
        ) return city.civ.getEra().baseUnitBuyCost
        return null
    }

    fun getCostForConstructionsIncreasingInPrice(baseCost: Int, increaseCost: Int, previouslyBought: Int): Int {
        return (baseCost + increaseCost / 2f * ( previouslyBought * previouslyBought + previouslyBought )).toInt()
    }
}



class RejectionReason(val type: RejectionReasonType,
                           val errorMessage: String = type.errorMessage,
                           val shouldShow: Boolean = type.shouldShow) {

    fun techPolicyEraWonderRequirements(): Boolean = type in techPolicyEraWonderRequirements

    fun hasAReasonToBeRemovedFromQueue(): Boolean = type in reasonsToDefinitivelyRemoveFromQueue

    fun isImportantRejection(): Boolean = type in orderedImportantRejectionTypes

    /** Returns the index of [orderedImportantRejectionTypes] with the smallest index having the
     * highest precedence */
    fun getRejectionPrecedence(): Int {
        return orderedImportantRejectionTypes.indexOf(type)
    }

    // Used for constant variables in the functions above
    private val techPolicyEraWonderRequirements = hashSetOf(
        RejectionReasonType.Obsoleted,
        RejectionReasonType.RequiresTech,
        RejectionReasonType.RequiresPolicy,
        RejectionReasonType.MorePolicyBranches,
        RejectionReasonType.RequiresBuildingInSomeCity,
    )
    private val reasonsToDefinitivelyRemoveFromQueue = hashSetOf(
        RejectionReasonType.Obsoleted,
        RejectionReasonType.WonderAlreadyBuilt,
        RejectionReasonType.NationalWonderAlreadyBuilt,
        RejectionReasonType.CannotBeBuiltWith,
        RejectionReasonType.MaxNumberBuildable,
    )
    private val orderedImportantRejectionTypes = listOf(
        RejectionReasonType.WonderBeingBuiltElsewhere,
        RejectionReasonType.NationalWonderBeingBuiltElsewhere,
        RejectionReasonType.RequiresBuildingInAllCities,
        RejectionReasonType.RequiresBuildingInThisCity,
        RejectionReasonType.RequiresBuildingInSomeCity,
        RejectionReasonType.PopulationRequirement,
        RejectionReasonType.ConsumesResources,
        RejectionReasonType.CanOnlyBePurchased,
        RejectionReasonType.MaxNumberBuildable,
        RejectionReasonType.NoPlaceToPutUnit,
    )
}


enum class RejectionReasonType(val shouldShow: Boolean, val errorMessage: String) {
    AlreadyBuilt(false, "Building already built in this city"),
    Unbuildable(false, "Unbuildable"),
    CanOnlyBePurchased(true, "Can only be purchased"),
    ShouldNotBeDisplayed(false, "Should not be displayed"),

    DisabledBySetting(false, "Disabled by setting"),
    HiddenWithoutVictory(false, "Hidden because a victory type has been disabled"),

    MustBeOnTile(false, "Must be on a specific tile"),
    MustNotBeOnTile(false, "Must not be on a specific tile"),
    MustBeNextToTile(false, "Must be next to a specific tile"),
    MustNotBeNextToTile(false, "Must not be next to a specific tile"),
    MustOwnTile(false, "Must own a specific tile close by"),
    WaterUnitsInCoastalCities(false, "May only built water units in coastal cities"),
    CanOnlyBeBuiltInSpecificCities(false, "Can only be built in specific cities"),
    MaxNumberBuildable(false, "Maximum number have been built or are being constructed"),

    UniqueToOtherNation(false, "Unique to another nation"),
    ReplacedByOurUnique(false, "Our unique replaces this"),
    CannotBeBuilt(false, "Cannot be built by this nation"),

    Obsoleted(false, "Obsolete"),
    RequiresTech(false, "Required tech not researched"),
    RequiresPolicy(false, "Requires a specific policy!"),
    UnlockedWithEra(false, "Unlocked when reaching a specific era"),
    MorePolicyBranches(false, "Hidden until more policy branches are fully adopted"),

    RequiresNearbyResource(false, "Requires a certain resource being exploited nearby"),
    CannotBeBuiltWith(false, "Cannot be built at the same time as another building already built"),

    RequiresBuildingInThisCity(true, "Requires a specific building in this city!"),
    RequiresBuildingInAllCities(true, "Requires a specific building in all cities!"),
    RequiresBuildingInSomeCity(true, "Requires a specific building anywhere in your empire!"),

    WonderAlreadyBuilt(false, "Wonder already built"),
    NationalWonderAlreadyBuilt(false, "National Wonder already built"),
    WonderBeingBuiltElsewhere(true, "Wonder is being built elsewhere"),
    NationalWonderBeingBuiltElsewhere(true, "National Wonder is being built elsewhere"),
    CityStateWonder(false, "No Wonders for city-states"),
    CityStateNationalWonder(false, "No National Wonders for city-states"),
    WonderDisabledEra(false, "This Wonder is disabled when starting in this era"),

    ConsumesResources(true, "Consumes resources which you are lacking"),

    PopulationRequirement(true, "Requires more population"),

    NoSettlerForOneCityPlayers(false, "No settlers for city-states or one-city challengers"),
    NoPlaceToPutUnit(true, "No space to place this unit");

    fun toInstance(errorMessage: String = this.errorMessage,
        shouldShow: Boolean = this.shouldShow): RejectionReason {
        return RejectionReason(this, errorMessage, shouldShow)
    }
}


open class PerpetualConstruction(override var name: String, val description: String) : IConstruction {

    override fun shouldBeDisplayed(cityConstructions: CityConstructions) = isBuildable(cityConstructions)
    open fun getProductionTooltip(city: City) : String = ""

    companion object {
        val science = PerpetualStatConversion(Stat.Science)
        val gold = PerpetualStatConversion(Stat.Gold)
        val culture = PerpetualStatConversion(Stat.Culture)
        val faith = PerpetualStatConversion(Stat.Faith)
        val idle = object : PerpetualConstruction("Nothing", "The city will not produce anything.") {
            override fun isBuildable(cityConstructions: CityConstructions): Boolean = true
        }

        val perpetualConstructionsMap: Map<String, PerpetualConstruction>
                = mapOf(science.name to science, gold.name to gold, culture.name to culture, faith.name to faith, idle.name to idle)
    }

    override fun isBuildable(cityConstructions: CityConstructions): Boolean =
            throw Exception("Impossible!")

    override fun getResourceRequirements(): HashMap<String, Int> = hashMapOf()

    override fun requiresResource(resource: String) = false

}

open class PerpetualStatConversion(val stat: Stat) :
    PerpetualConstruction(stat.name, "Convert production to [${stat.name}] at a rate of [rate] to 1") {

    override fun getProductionTooltip(city: City) : String
            = "\r\n${(city.cityStats.currentCityStats.production / getConversionRate(city)).roundToInt()}/${Fonts.turn}"
    fun getConversionRate(city: City) : Int = (1/city.cityStats.getStatConversionRate(stat)).roundToInt()

    override fun isBuildable(cityConstructions: CityConstructions): Boolean {
        if (stat == Stat.Faith && !cityConstructions.city.civ.gameInfo.isReligionEnabled())
            return false

        return cityConstructions.city.civ.getMatchingUniques(UniqueType.EnablesCivWideStatProduction)
            .any { it.params[0] == stat.name }
    }
}
