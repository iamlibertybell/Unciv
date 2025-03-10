package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.automation.Automation
import com.unciv.logic.automation.ThreatLevel
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.city.INonPerpetualConstruction
import com.unciv.logic.city.PerpetualConstruction
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.BFS
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeRequest
import com.unciv.logic.trade.TradeType
import com.unciv.models.Counter
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.ModOptionsConstants
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.ruleset.Victory
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.translations.tr
import com.unciv.ui.victoryscreen.RankingType
import kotlin.math.min

object NextTurnAutomation {

    /** Top-level AI turn task list */
    fun automateCivMoves(civInfo: Civilization) {
        if (civInfo.isBarbarian()) return BarbarianAutomation(civInfo).automate()

        respondToPopupAlerts(civInfo)
        respondToTradeRequests(civInfo)

        if (civInfo.isMajorCiv()) {
            if (!civInfo.gameInfo.ruleSet.modOptions.hasUnique(ModOptionsConstants.diplomaticRelationshipsCannotChange)) {
                declareWar(civInfo)
                offerPeaceTreaty(civInfo)
//            offerDeclarationOfFriendship(civInfo)
            }
            if (civInfo.gameInfo.isReligionEnabled()) {
                ReligionAutomation.spendFaithOnReligion(civInfo)
            }
            offerResearchAgreement(civInfo)
            exchangeLuxuries(civInfo)
            issueRequests(civInfo)
            adoptPolicy(civInfo)  // todo can take a second - why?
            freeUpSpaceResources(civInfo)
        } else {
            civInfo.cityStateFunctions.getFreeTechForCityState()
            civInfo.cityStateFunctions.updateDiplomaticRelationshipForCityState()
        }

        chooseTechToResearch(civInfo)
        automateCityBombardment(civInfo)
        useGold(civInfo)
        if (!civInfo.isCityState()) {
            protectCityStates(civInfo)
            bullyCityStates(civInfo)
        }
        automateUnits(civInfo)  // this is the most expensive part

        if (civInfo.isMajorCiv()) {
            // Can only be done now, as the prophet first has to decide to found/enhance a religion
            chooseReligiousBeliefs(civInfo)
        }

        automateCities(civInfo)  // second most expensive
        trainSettler(civInfo)
        tryVoteForDiplomaticVictory(civInfo)
    }

    fun automateGoldToSciencePercentage(civInfo: Civilization) {
        // Don't let the AI run blindly with the default convert-gold-to-science ratio if that option is enabled
        val estimatedIncome = civInfo.stats.statsForNextTurn.gold.toInt()
        val projectedGold = civInfo.gold + estimatedIncome
        // TODO: some cleverness, this is just wild guessing.
        val pissPoor = civInfo.tech.era.baseUnitBuyCost
        val stinkingRich = civInfo.tech.era.startingGold * 10 + civInfo.cities.size * 2 * pissPoor
        val maxPercent = 0.8f
        civInfo.tech.goldPercentConvertedToScience = when {
            civInfo.gold <= 0 -> 0f
            projectedGold <= pissPoor -> 0f
            else -> ((projectedGold - pissPoor) * maxPercent / stinkingRich).coerceAtMost(maxPercent)
        }
    }

    private fun respondToTradeRequests(civInfo: Civilization) {
        for (tradeRequest in civInfo.tradeRequests.toList()) {
            val otherCiv = civInfo.gameInfo.getCivilization(tradeRequest.requestingCiv)
            if (!TradeEvaluation().isTradeValid(tradeRequest.trade, civInfo, otherCiv))
                continue

            val tradeLogic = TradeLogic(civInfo, otherCiv)
            tradeLogic.currentTrade.set(tradeRequest.trade)
            /** We need to remove this here, so that if the trade is accepted, the updateDetailedCivResources()
             * in tradeLogic.acceptTrade() will not consider *both* the trade *and the trade offer as decreasing the
             * amount of available resources, since that will lead to "Our proposed trade is no longer valid" if we try to offer
             * the same resource to ANOTHER civ in this turn. Complicated!
             */
            civInfo.tradeRequests.remove(tradeRequest)
            if (TradeEvaluation().isTradeAcceptable(tradeLogic.currentTrade, civInfo, otherCiv)) {
                tradeLogic.acceptTrade()
                otherCiv.addNotification("[${civInfo.civName}] has accepted your trade request", NotificationCategory.Trade, NotificationIcon.Trade, civInfo.civName)
            } else {
                val counteroffer = getCounteroffer(civInfo, tradeRequest)
                if (counteroffer != null) {
                    otherCiv.addNotification("[${civInfo.civName}] has made a counteroffer to your trade request", NotificationCategory.Trade, NotificationIcon.Trade, civInfo.civName)
                    otherCiv.tradeRequests.add(counteroffer)
                } else
                    tradeRequest.decline(civInfo)
            }
        }
        civInfo.tradeRequests.clear()
    }

    /** @return a TradeRequest with the same ourOffers as [tradeRequest] but with enough theirOffers
     *  added to make the deal acceptable. Will find a valid counteroffer if any exist, but is not
     *  guaranteed to find the best or closest one. */
    private fun getCounteroffer(civInfo: Civilization, tradeRequest: TradeRequest): TradeRequest? {
        val otherCiv = civInfo.gameInfo.getCivilization(tradeRequest.requestingCiv)
        // AIs counteroffering each other is problematic as they tend to ping-pong back and forth forever
        if (otherCiv.playerType == PlayerType.AI)
            return null
        val evaluation = TradeEvaluation()
        var deltaInOurFavor = evaluation.getTradeAcceptability(tradeRequest.trade, civInfo, otherCiv)
        if (deltaInOurFavor > 0) deltaInOurFavor = (deltaInOurFavor / 1.1f).toInt() // They seem very interested in this deal, let's push it a bit.
        val tradeLogic = TradeLogic(civInfo, otherCiv)

        tradeLogic.currentTrade.set(tradeRequest.trade)

        // What do they have that we would want?
        val potentialAsks = HashMap<TradeOffer, Int>()
        val counterofferAsks = HashMap<TradeOffer, Int>()
        val counterofferGifts = ArrayList<TradeOffer>()

        for (offer in tradeLogic.theirAvailableOffers) {
            if ((offer.type == TradeType.Gold || offer.type == TradeType.Gold_Per_Turn)
                    && tradeRequest.trade.ourOffers.any { it.type == offer.type })
                    continue // Don't want to counteroffer straight gold for gold, that's silly
            if (!offer.isTradable())
                continue // For example resources gained by trade or CS
            if (offer.type == TradeType.City)
                continue // Players generally don't want to give up their cities, and they might misclick

            if (tradeLogic.currentTrade.theirOffers.any { it.type == offer.type && it.name == offer.name })
                continue // So you don't get double offers of open borders declarations of war etc.

            val value = evaluation.evaluateBuyCost(offer, civInfo, otherCiv)
            if (value > 0)
                potentialAsks[offer] = value
        }

        while (potentialAsks.isNotEmpty() && deltaInOurFavor < 0) {
            // Keep adding their worst offer until we get above the threshold
            val offerToAdd = potentialAsks.minByOrNull { it.value }!!
            deltaInOurFavor += offerToAdd.value
            counterofferAsks[offerToAdd.key] = offerToAdd.value
            potentialAsks.remove(offerToAdd.key)
        }
        if (deltaInOurFavor < 0)
            return null // We couldn't get a good enough deal

        // At this point we are sure to find a good counteroffer
        while (deltaInOurFavor > 0) {
            // Now remove the best offer valued below delta until the deal is barely acceptable
            val offerToRemove = counterofferAsks.filter { it.value <= deltaInOurFavor }.maxByOrNull { it.value }
                ?: break  // Nothing more can be removed, at least en bloc
            deltaInOurFavor -= offerToRemove.value
            counterofferAsks.remove(offerToRemove.key)
        }

        // Only ask for enough of each resource to get maximum price
        for (ask in counterofferAsks.keys.filter { it.type == TradeType.Luxury_Resource || it.type == TradeType.Strategic_Resource }) {
            // Remove 1 amount as long as doing so does not change the price
            val originalValue = counterofferAsks[ask]!!
            while (ask.amount > 1
                    && originalValue == evaluation.evaluateBuyCost(
                            TradeOffer(ask.name, ask.type, ask.amount - 1, ask.duration),
                            civInfo, otherCiv) ) {
                ask.amount--
            }
        }

        // Adjust any gold asked for
        val toRemove = ArrayList<TradeOffer>()
        for (goldAsk in counterofferAsks.keys
                .filter { it.type == TradeType.Gold_Per_Turn || it.type == TradeType.Gold }
                .sortedByDescending { it.type.ordinal }) { // Do GPT first
            val valueOfOne = evaluation.evaluateBuyCost(TradeOffer(goldAsk.name, goldAsk.type, 1, goldAsk.duration), civInfo, otherCiv)
            val amountCanBeRemoved = deltaInOurFavor / valueOfOne
            if (amountCanBeRemoved >= goldAsk.amount) {
                deltaInOurFavor -= counterofferAsks[goldAsk]!!
                toRemove.add(goldAsk)
            } else {
                deltaInOurFavor -= valueOfOne * amountCanBeRemoved
                goldAsk.amount -= amountCanBeRemoved
            }
        }

        // If the delta is still very in our favor consider sweetening the pot with some gold
        if (deltaInOurFavor >= 100) {
            deltaInOurFavor = (deltaInOurFavor * 2) / 3 // Only compensate some of it though, they're the ones asking us
            // First give some GPT, then lump sum - but only if they're not already offering the same
            for (ourGold in tradeLogic.ourAvailableOffers
                    .filter { it.isTradable() && (it.type == TradeType.Gold || it.type == TradeType.Gold_Per_Turn) }
                    .sortedByDescending { it.type.ordinal }) {
                if (tradeLogic.currentTrade.theirOffers.none { it.type == ourGold.type } &&
                        counterofferAsks.keys.none { it.type == ourGold.type } ) {
                    val valueOfOne = evaluation.evaluateSellCost(TradeOffer(ourGold.name, ourGold.type, 1, ourGold.duration), civInfo, otherCiv)
                    val amountToGive = min(deltaInOurFavor / valueOfOne, ourGold.amount)
                    deltaInOurFavor -= amountToGive * valueOfOne
                    counterofferGifts.add(TradeOffer(ourGold.name, ourGold.type, amountToGive, ourGold.duration))
                }
            }
        }

        tradeLogic.currentTrade.theirOffers.addAll(counterofferAsks.keys)
        tradeLogic.currentTrade.ourOffers.addAll(counterofferGifts)

        // Trades reversed, because when *they* get it then the 'ouroffers' become 'theiroffers'
        return TradeRequest(civInfo.civName, tradeLogic.currentTrade.reverse())
    }

    private fun respondToPopupAlerts(civInfo: Civilization) {
        for (popupAlert in civInfo.popupAlerts) {
            if (popupAlert.type == AlertType.DemandToStopSettlingCitiesNear) {  // we're called upon to make a decision
                val demandingCiv = civInfo.gameInfo.getCivilization(popupAlert.value)
                val diploManager = civInfo.getDiplomacyManager(demandingCiv)
                if (Automation.threatAssessment(civInfo, demandingCiv) >= ThreatLevel.High)
                    diploManager.agreeNotToSettleNear()
                else diploManager.refuseDemandNotToSettleNear()
            }
            if (popupAlert.type == AlertType.DeclarationOfFriendship) {
                val requestingCiv = civInfo.gameInfo.getCivilization(popupAlert.value)
                val diploManager = civInfo.getDiplomacyManager(requestingCiv)
                if (diploManager.relationshipLevel() > RelationshipLevel.Neutral
                        && !diploManager.otherCivDiplomacy().hasFlag(DiplomacyFlags.Denunciation)) {
                    diploManager.signDeclarationOfFriendship()
                    requestingCiv.addNotification("We have signed a Declaration of Friendship with [${civInfo.civName}]!", NotificationCategory.Diplomacy, NotificationIcon.Diplomacy, civInfo.civName)
                } else requestingCiv.addNotification("[${civInfo.civName}] has denied our Declaration of Friendship!", NotificationCategory.Diplomacy, NotificationIcon.Diplomacy, civInfo.civName)
            }
        }

        civInfo.popupAlerts.clear() // AIs don't care about popups.
    }

    private fun tryGainInfluence(civInfo: Civilization, cityState: Civilization) {
        if (civInfo.gold < 250) return // save up
        if (cityState.getDiplomacyManager(civInfo).getInfluence() < 20) {
            cityState.cityStateFunctions.receiveGoldGift(civInfo, 250)
            return
        }
        if (civInfo.gold < 500) return // it's not worth it to invest now, wait until you have enough for 2
        cityState.cityStateFunctions.receiveGoldGift(civInfo, 500)
        return
    }

    /** allow AI to spend money to purchase city-state friendship, buildings & unit */
    private fun useGold(civInfo: Civilization) {
        if (civInfo.getHappiness() > 0 && civInfo.hasUnique(UniqueType.CityStateCanBeBoughtForGold)) {
            for (cityState in civInfo.getKnownCivs().filter { it.isCityState() } ) {
                if (cityState.cityStateFunctions.canBeMarriedBy(civInfo))
                    cityState.cityStateFunctions.diplomaticMarriage(civInfo)
                if (civInfo.getHappiness() <= 0) break // Stop marrying if happiness is getting too low
            }
        }

        if (civInfo.wantsToFocusOn(Victory.Focus.Culture)) {
            for (cityState in civInfo.getKnownCivs()
                    .filter { it.isCityState() && it.cityStateFunctions.canProvideStat(Stat.Culture) }) {
                val diploManager = cityState.getDiplomacyManager(civInfo)
                if (diploManager.getInfluence() < 40) { // we want to gain influence with them
                    tryGainInfluence(civInfo, cityState)
                    return
                }
            }
        }

        if (!civInfo.isCityState()) {
            val potentialAllies = civInfo.getKnownCivs().filter { it.isCityState() }
            if (potentialAllies.isNotEmpty()) {
                val cityState =
                    potentialAllies.maxByOrNull { valueCityStateAlliance(civInfo, it) }!!
                if (cityState.getAllyCiv() != civInfo.civName && valueCityStateAlliance(civInfo, cityState) > 0) {
                    tryGainInfluence(civInfo, cityState)
                    return
                }
            }
        }

        for (city in civInfo.cities.sortedByDescending { it.population.population }) {
            val construction = city.cityConstructions.getCurrentConstruction()
            if (construction is PerpetualConstruction) continue
            if ((construction as INonPerpetualConstruction).canBePurchasedWithStat(city, Stat.Gold)
                    && city.civ.gold / 3 >= construction.getStatBuyCost(city, Stat.Gold)!!) {
                city.cityConstructions.purchaseConstruction(construction.name, 0, true)
            }
        }
    }

    private fun valueCityStateAlliance(civInfo: Civilization, cityState: Civilization): Int {
        var value = 0

        if (civInfo.wantsToFocusOn(Victory.Focus.Culture) && cityState.cityStateFunctions.canProvideStat(Stat.Culture)) {
            value += 10
        }
        else if (civInfo.wantsToFocusOn(Victory.Focus.Science) && cityState.cityStateFunctions.canProvideStat(Stat.Science)) {
            // In case someone mods this in
            value += 10
        }
        else if (civInfo.wantsToFocusOn(Victory.Focus.Military)) {
            if (!cityState.isAlive())
                value -= 5
            else {
                // Don't ally close city-states, conquer them instead
                val distance = getMinDistanceBetweenCities(civInfo, cityState)
                if (distance < 20)
                    value -= (20 - distance) / 4
            }
        }
        else if (civInfo.wantsToFocusOn(Victory.Focus.CityStates)) {
            value += 5  // Generally be friendly
        }
        if (civInfo.getHappiness() < 5 && cityState.cityStateFunctions.canProvideStat(Stat.Happiness)) {
            value += 10 - civInfo.getHappiness()
        }
        if (civInfo.getHappiness() > 5 && cityState.cityStateFunctions.canProvideStat(Stat.Food)) {
            value += 5
        }

        if (!cityState.isAlive() || cityState.cities.isEmpty() || civInfo.cities.isEmpty())
            return value

        if (civInfo.gold < 100) {
            // Consider bullying for cash
            value -= 5
        }

        if (cityState.getAllyCiv() != null && cityState.getAllyCiv() != civInfo.civName) {
            // easier not to compete if a third civ has this locked down
            val thirdCivInfluence = cityState.getDiplomacyManager(cityState.getAllyCiv()!!).getInfluence().toInt()
            value -= (thirdCivInfluence - 60) / 10
        }

        // Bonus for luxury resources we can get from them
        value += cityState.detailedCivResources.count {
            it.resource.resourceType == ResourceType.Luxury
            && it.resource !in civInfo.detailedCivResources.map { supply -> supply.resource }
        }

        return value
    }

    private fun protectCityStates(civInfo: Civilization) {
        for (state in civInfo.getKnownCivs().filter{!it.isDefeated() && it.isCityState()}) {
            val diplomacyManager = state.getDiplomacyManager(civInfo.civName)
            if(diplomacyManager.relationshipLevel() >= RelationshipLevel.Friend
                && state.cityStateFunctions.otherCivCanPledgeProtection(civInfo))
            {
                state.cityStateFunctions.addProtectorCiv(civInfo)
            } else if (diplomacyManager.relationshipLevel() < RelationshipLevel.Friend
                && state.cityStateFunctions.otherCivCanWithdrawProtection(civInfo)) {
                state.cityStateFunctions.removeProtectorCiv(civInfo)
            }
        }
    }

    private fun bullyCityStates(civInfo: Civilization) {
        for (state in civInfo.getKnownCivs().filter{!it.isDefeated() && it.isCityState()}) {
            val diplomacyManager = state.getDiplomacyManager(civInfo.civName)
            if(diplomacyManager.relationshipLevel() < RelationshipLevel.Friend
                    && diplomacyManager.diplomaticStatus == DiplomaticStatus.Peace
                    && valueCityStateAlliance(civInfo, state) <= 0
                    && state.cityStateFunctions.getTributeWillingness(civInfo) >= 0) {
                if (state.cityStateFunctions.getTributeWillingness(civInfo, demandingWorker = true) > 0)
                    state.cityStateFunctions.tributeWorker(civInfo)
                else
                    state.cityStateFunctions.tributeGold(civInfo)
            }
        }
    }

    private fun chooseTechToResearch(civInfo: Civilization) {
        if (civInfo.tech.techsToResearch.isEmpty()) {
            val researchableTechs = civInfo.gameInfo.ruleSet.technologies.values
                    .filter { civInfo.tech.canBeResearched(it.name) }
            val techsGroups = researchableTechs.groupBy { it.cost }
            val costs = techsGroups.keys.sorted()

            if (researchableTechs.isEmpty()) return

            val cheapestTechs = techsGroups[costs[0]]!!
            //Do not consider advanced techs if only one tech left in cheapest group
            val techToResearch: Technology =
                if (cheapestTechs.size == 1 || costs.size == 1) {
                    cheapestTechs.random()
                } else {
                    //Choose randomly between cheapest and second cheapest group
                    val techsAdvanced = techsGroups[costs[1]]!!
                    (cheapestTechs + techsAdvanced).random()
                }

            civInfo.tech.techsToResearch.add(techToResearch.name)
        }
    }

    private fun adoptPolicy(civInfo: Civilization) {
        /*
        # Branch-based policy-to-adopt decision
        Basically the AI prioritizes finishing incomplete branches before moving on, \
        unless a new branch with higher priority is adoptable.

        - If incomplete branches have higher priorities than any newly adoptable branch,
            - Candidates are the unfinished branches.
        - Else if newly adoptable branches have higher priorities than any incomplete branch,
            - Candidates are the new branches.
        - Choose a random candidate closest to completion.
        - Pick a random child policy of a chosen branch and adopt it.
        */
        while (civInfo.policies.canAdoptPolicy()) {
            val incompleteBranches: Set<PolicyBranch> = civInfo.policies.incompleteBranches
            val adoptableBranches: Set<PolicyBranch> = civInfo.policies.adoptableBranches

            // Skip the whole thing if all branches are completed
            if (incompleteBranches.isEmpty() && adoptableBranches.isEmpty()) return

            val priorityMap: Map<PolicyBranch, Int> = civInfo.policies.priorityMap
            var maxIncompletePriority: Int? =
                civInfo.policies.getMaxPriority(incompleteBranches)
            var maxAdoptablePriority: Int? = civInfo.policies.getMaxPriority(adoptableBranches)

            // This here is a (probably dirty) code to bypass NoSuchElementException error
            //  when one of the priority variables is null
            if (maxIncompletePriority == null) maxIncompletePriority =
                maxAdoptablePriority!! - 1
            if (maxAdoptablePriority == null) maxAdoptablePriority =
                maxIncompletePriority - 1

            // Candidate branches to adopt
            val candidates: Set<PolicyBranch> =
                // If incomplete branches have higher priorities than any newly adoptable branch,
                if (maxAdoptablePriority <= maxIncompletePriority) {
                    // Prioritize finishing one of the unfinished branches
                    incompleteBranches.filter {
                        priorityMap[it] == maxIncompletePriority
                    }.toSet()
                }
                // If newly adoptable branches have higher priorities than any incomplete branch,
                else {
                    // Prioritize adopting one of the new branches
                    adoptableBranches.filter {
                        priorityMap[it] == maxAdoptablePriority
                    }.toSet()
                }

            // branchCompletionMap but keys are only candidates
            val candidateCompletionMap: Map<PolicyBranch, Int> =
                civInfo.policies.branchCompletionMap.filterKeys { key ->
                    key in candidates
                }
            // The highest number of adopted child policies within a single candidate
            val maxCompletion: Int =
                candidateCompletionMap.maxOf { entry -> entry.value }
            // The candidate closest to completion, hence the target branch
            val targetBranch = candidateCompletionMap.filterValues { value ->
                value == maxCompletion
            }.keys.random()

            val policyToAdopt: Policy =
                if (civInfo.policies.isAdoptable(targetBranch)) targetBranch
                else targetBranch.policies.filter { civInfo.policies.isAdoptable(it) }.random()

            civInfo.policies.adopt(policyToAdopt)
        }
    }

    /** If we are able to build a spaceship but have already spent our resources, try disbanding
     *  a unit and selling a building to make room. Can happen due to trades etc */
    private fun freeUpSpaceResources(civInfo: Civilization) {
        // No need to build spaceship parts just yet
        if (civInfo.gameInfo.ruleSet.victories.none { civInfo.victoryManager.getNextMilestone(it.key)?.type == MilestoneType.AddedSSPartsInCapital } )
            return

        for (resource in civInfo.gameInfo.spaceResources) {
            // Have enough resources already
            val resourceCount = civInfo.getCivResourcesByName()[resource] ?: 0
            if (resourceCount >= Automation.getReservedSpaceResourceAmount(civInfo))
                continue

            val unitToDisband = civInfo.units.getCivUnits()
                .filter { it.baseUnit.requiresResource(resource) }
                .minByOrNull { it.getForceEvaluation() }
            unitToDisband?.disband()

            for (city in civInfo.cities) {
                if (city.hasSoldBuildingThisTurn)
                    continue
                val buildingToSell = civInfo.gameInfo.ruleSet.buildings.values.filter {
                        it.name in city.cityConstructions.builtBuildings
                        && it.requiresResource(resource)
                        && it.isSellable()
                        && it.name !in civInfo.civConstructions.getFreeBuildings(city.id) }
                    .randomOrNull()
                if (buildingToSell != null) {
                    city.sellBuilding(buildingToSell.name)
                    break
                }
            }
        }
    }

    private fun chooseReligiousBeliefs(civInfo: Civilization) {
        choosePantheon(civInfo)
        foundReligion(civInfo)
        enhanceReligion(civInfo)
        chooseFreeBeliefs(civInfo)
    }

    private fun choosePantheon(civInfo: Civilization) {
        if (!civInfo.religionManager.canFoundOrExpandPantheon()) return
        // So looking through the source code of the base game available online,
        // the functions for choosing beliefs total in at around 400 lines.
        // https://github.com/Gedemon/Civ5-DLL/blob/aa29e80751f541ae04858b6d2a2c7dcca454201e/CvGameCoreDLL_Expansion1/CvReligionClasses.cpp
        // line 4426 through 4870.
        // This is way too much work for now, so I'll just choose a random pantheon instead.
        // Should probably be changed later, but it works for now.
        val chosenPantheon = chooseBeliefOfType(civInfo, BeliefType.Pantheon)
            ?: return // panic!
        civInfo.religionManager.chooseBeliefs(
            listOf(chosenPantheon),
            useFreeBeliefs = civInfo.religionManager.usingFreeBeliefs()
        )
    }

    private fun foundReligion(civInfo: Civilization) {
        if (civInfo.religionManager.religionState != ReligionState.FoundingReligion) return
        val availableReligionIcons = civInfo.gameInfo.ruleSet.religions
            .filterNot { civInfo.gameInfo.religions.values.map { religion -> religion.name }.contains(it) }
        val religionIcon =
            if (civInfo.nation.favoredReligion in availableReligionIcons) civInfo.nation.favoredReligion
            else availableReligionIcons.randomOrNull()
                ?: return // Wait what? How did we pass the checking when using a great prophet but not this?
        val chosenBeliefs = chooseBeliefs(civInfo, civInfo.religionManager.getBeliefsToChooseAtFounding()).toList()
        civInfo.religionManager.chooseBeliefs(chosenBeliefs, religionIcon, religionIcon)
    }

    private fun enhanceReligion(civInfo: Civilization) {
        if (civInfo.religionManager.religionState != ReligionState.EnhancingReligion) return
        civInfo.religionManager.chooseBeliefs(
            chooseBeliefs(civInfo, civInfo.religionManager.getBeliefsToChooseAtEnhancing()).toList()
        )
    }

    private fun chooseFreeBeliefs(civInfo: Civilization) {
        if (!civInfo.religionManager.hasFreeBeliefs()) return
        civInfo.religionManager.chooseBeliefs(
            chooseBeliefs(civInfo, civInfo.religionManager.freeBeliefsAsEnums()).toList(),
            useFreeBeliefs = true
        )
    }

    private fun chooseBeliefs(civInfo: Civilization, beliefsToChoose: Counter<BeliefType>): HashSet<Belief> {
        val chosenBeliefs = hashSetOf<Belief>()
        // The `continue`s should never be reached, but just in case I'd rather have the AI have a
        // belief less than make the game crash. The `continue`s should only be reached whenever
        // there are not enough beliefs to choose, but there should be, as otherwise we could
        // not have used a great prophet to found/enhance our religion.
        for (belief in BeliefType.values()) {
            if (belief == BeliefType.None) continue
            for (counter in 0 until (beliefsToChoose[belief] ?: 0))
                chosenBeliefs.add(
                    chooseBeliefOfType(civInfo, belief, chosenBeliefs) ?: continue
                )
        }
        return chosenBeliefs
    }

    private fun chooseBeliefOfType(civInfo: Civilization, beliefType: BeliefType, additionalBeliefsToExclude: HashSet<Belief> = hashSetOf()): Belief? {
        return civInfo.gameInfo.ruleSet.beliefs
            .filter {
                (it.value.type == beliefType || beliefType == BeliefType.Any)
                && !additionalBeliefsToExclude.contains(it.value)
                && civInfo.religionManager.getReligionWithBelief(it.value) == null
            }
            .map { it.value }
            .maxByOrNull { ReligionAutomation.rateBelief(civInfo, it) }
    }

    private fun potentialLuxuryTrades(civInfo: Civilization, otherCivInfo: Civilization): ArrayList<Trade> {
        val tradeLogic = TradeLogic(civInfo, otherCivInfo)
        val ourTradableLuxuryResources = tradeLogic.ourAvailableOffers
                .filter { it.type == TradeType.Luxury_Resource && it.amount > 1 }
        val theirTradableLuxuryResources = tradeLogic.theirAvailableOffers
                .filter { it.type == TradeType.Luxury_Resource && it.amount > 1 }
        val weHaveTheyDont = ourTradableLuxuryResources
                .filter { resource ->
                    tradeLogic.theirAvailableOffers
                            .none { it.name == resource.name && it.type == TradeType.Luxury_Resource }
                }
        val theyHaveWeDont = theirTradableLuxuryResources
                .filter { resource ->
                    tradeLogic.ourAvailableOffers
                            .none { it.name == resource.name && it.type == TradeType.Luxury_Resource }
                }.sortedBy { civInfo.cities.count { city -> city.demandedResource == it.name } } // Prioritize resources that get WLTKD
        val trades = ArrayList<Trade>()
        for (i in 0..min(weHaveTheyDont.lastIndex, theyHaveWeDont.lastIndex)) {
            val trade = Trade()
            trade.ourOffers.add(weHaveTheyDont[i].copy(amount = 1))
            trade.theirOffers.add(theyHaveWeDont[i].copy(amount = 1))
            trades.add(trade)
        }
        return trades
    }

    private fun exchangeLuxuries(civInfo: Civilization) {
        val knownCivs = civInfo.getKnownCivs()

        // Player trades are... more complicated.
        // When the AI offers a trade, it's not immediately accepted,
        // so what if it thinks that it has a spare luxury and offers it to two human players?
        // What's to stop the AI "nagging" the player to accept a luxury trade?
        // We should A. add some sort of timer (20? 30 turns?) between luxury trade requests if they're denied - see DeclinedLuxExchange
        // B. have a way for the AI to keep track of the "pending offers" - see DiplomacyManager.resourcesFromTrade

        for (otherCiv in knownCivs.filter {
            it.isMajorCiv() && !it.isAtWarWith(civInfo)
                    && !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclinedLuxExchange)
        }) {

            val relationshipLevel = civInfo.getDiplomacyManager(otherCiv).relationshipLevel()
            if (relationshipLevel <= RelationshipLevel.Enemy)
                continue

            val trades = potentialLuxuryTrades(civInfo, otherCiv)
            for (trade in trades) {
                val tradeRequest = TradeRequest(civInfo.civName, trade.reverse())
                otherCiv.tradeRequests.add(tradeRequest)
            }
        }
    }

    @Suppress("unused")  //todo: Work in Progress?
    private fun offerDeclarationOfFriendship(civInfo: Civilization) {
        val civsThatWeCanDeclareFriendshipWith = civInfo.getKnownCivs()
                .asSequence()
                .filter {
                    it.isMajorCiv() && !it.isAtWarWith(civInfo)
                            && it.getDiplomacyManager(civInfo).relationshipLevel() > RelationshipLevel.Neutral
                            && !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclarationOfFriendship)
                            && !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.Denunciation)
                }
                .sortedByDescending { it.getDiplomacyManager(civInfo).relationshipLevel() }
        for (civ in civsThatWeCanDeclareFriendshipWith) {
            // Default setting is 5, this will be changed according to different civ.
            if ((1..10).random() <= 5)
                civInfo.getDiplomacyManager(civ).signDeclarationOfFriendship()
        }
    }

    private fun offerResearchAgreement(civInfo: Civilization) {
        if (!civInfo.diplomacyFunctions.canSignResearchAgreement()) return // don't waste your time

        val canSignResearchAgreementCiv = civInfo.getKnownCivs()
                .asSequence()
                .filter {
                    civInfo.diplomacyFunctions.canSignResearchAgreementsWith(it)
                            && !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclinedResearchAgreement)
                }
                .sortedByDescending { it.stats.statsForNextTurn.science }

        for (otherCiv in canSignResearchAgreementCiv) {
            // Default setting is 5, this will be changed according to different civ.
            if ((1..10).random() > 5) continue
            val tradeLogic = TradeLogic(civInfo, otherCiv)
            val cost = civInfo.diplomacyFunctions.getResearchAgreementCost()
            tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.researchAgreement, TradeType.Treaty, cost))
            tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.researchAgreement, TradeType.Treaty, cost))

            otherCiv.tradeRequests.add(TradeRequest(civInfo.civName, tradeLogic.currentTrade.reverse()))
        }
    }

    private fun declareWar(civInfo: Civilization) {
        if (civInfo.wantsToFocusOn(Victory.Focus.Culture)) return
        if (civInfo.cities.isEmpty() || civInfo.diplomacy.isEmpty()) return
        if (civInfo.isAtWar() || civInfo.getHappiness() <= 0) return

        val ourMilitaryUnits = civInfo.units.getCivUnits().filter { !it.isCivilian() }.count()
        if (ourMilitaryUnits < civInfo.cities.size) return
        if (ourMilitaryUnits < 4) return  // to stop AI declaring war at the beginning of games when everyone isn't set up well enough\
        if (civInfo.cities.size < 3) return // FAR too early for that what are you thinking!

        //evaluate war
        val enemyCivs = civInfo.getKnownCivs()
                .filterNot {
                    it == civInfo || it.cities.isEmpty() || !civInfo.getDiplomacyManager(it).canDeclareWar()
                            || it.cities.none { city -> civInfo.hasExplored(city.getCenterTile()) }
                }
        // If the AI declares war on a civ without knowing the location of any cities, it'll just keep amassing an army and not sending it anywhere,
        //   and end up at a massive disadvantage

        if (enemyCivs.isEmpty()) return

        val civWithBestMotivationToAttack = enemyCivs
                .map { Pair(it, motivationToAttack(civInfo, it)) }
                .maxByOrNull { it.second }!!

        if (civWithBestMotivationToAttack.second >= 20)
            civInfo.getDiplomacyManager(civWithBestMotivationToAttack.first).declareWar()
    }

    private fun motivationToAttack(civInfo: Civilization, otherCiv: Civilization): Int {
        val closestCities = getClosestCities(civInfo, otherCiv) ?: return 0
        val baseForce = 30f

        val ourCombatStrength = civInfo.getStatForRanking(RankingType.Force).toFloat() + baseForce + CityCombatant(civInfo.getCapital()!!).getCityStrength()
        var theirCombatStrength = otherCiv.getStatForRanking(RankingType.Force).toFloat() + baseForce + CityCombatant(otherCiv.getCapital()!!).getCityStrength()

        //for city-states, also consider their protectors
        if (otherCiv.isCityState() and otherCiv.cityStateFunctions.getProtectorCivs().isNotEmpty()) {
            theirCombatStrength += otherCiv.cityStateFunctions.getProtectorCivs().filterNot { it == civInfo }
                .sumOf { it.getStatForRanking(RankingType.Force) }
        }

        if (theirCombatStrength > ourCombatStrength) return 0

        val ourCity = closestCities.city1
        val theirCity = closestCities.city2

        if (civInfo.units.getCivUnits().filter { it.isMilitary() }.none {
                val damageReceivedWhenAttacking =
                    BattleDamage.calculateDamageToAttacker(
                        MapUnitCombatant(it),
                        CityCombatant(theirCity)
                    )
                damageReceivedWhenAttacking < 100
            })
                return 0 // You don't have any units that can attack this city without dying, don't declare war.

        fun isTileCanMoveThrough(tile: Tile): Boolean {
            val owner = tile.getOwner()
            return !tile.isImpassible()
                    && (owner == otherCiv || owner == null || civInfo.diplomacyFunctions.canPassThroughTiles(owner))
        }

        val reachableEnemyCitiesBfs = BFS(civInfo.getCapital()!!.getCenterTile()) { isTileCanMoveThrough(it) }
        reachableEnemyCitiesBfs.stepToEnd()
        val reachableEnemyCities = otherCiv.cities.filter { reachableEnemyCitiesBfs.hasReachedTile(it.getCenterTile()) }
        if (reachableEnemyCities.isEmpty()) return 0 // Can't even reach the enemy city, no point in war.


        val modifierMap = HashMap<String, Int>()
        val combatStrengthRatio = ourCombatStrength / theirCombatStrength
        val combatStrengthModifier = when {
            combatStrengthRatio > 3f -> 30
            combatStrengthRatio > 2.5f -> 25
            combatStrengthRatio > 2f -> 20
            combatStrengthRatio > 1.5f -> 10
            else -> 0
        }
        modifierMap["Relative combat strength"] = combatStrengthModifier


        if (closestCities.aerialDistance > 7)
            modifierMap["Far away cities"] = -10

        val landPathBFS = BFS(ourCity.getCenterTile()) {
            it.isLand && isTileCanMoveThrough(it)
        }

        landPathBFS.stepUntilDestination(theirCity.getCenterTile())
        if (!landPathBFS.hasReachedTile(theirCity.getCenterTile()))
            modifierMap["No land path"] = -10

        val diplomacyManager = civInfo.getDiplomacyManager(otherCiv)
        if (diplomacyManager.hasFlag(DiplomacyFlags.ResearchAgreement))
            modifierMap["Research Agreement"] = -5

        if (diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
            modifierMap["Declaration of Friendship"] = -10

        val relationshipModifier = when (diplomacyManager.relationshipLevel()) {
            RelationshipLevel.Unforgivable -> 10
            RelationshipLevel.Enemy -> 5
            RelationshipLevel.Ally -> -5 // this is so that ally + DoF is not too unbalanced -
            // still possible for AI to declare war for isolated city
            else -> 0
        }
        modifierMap["Relationship"] = relationshipModifier

        if (diplomacyManager.resourcesFromTrade().any { it.amount > 0 })
            modifierMap["Receiving trade resources"] = -5

        if (theirCity.getTiles().none { tile -> tile.neighbors.any { it.getOwner() == theirCity.civ && it.getCity() != theirCity } })
            modifierMap["Isolated city"] = 15

        if (otherCiv.isCityState()) {
            modifierMap["City-state"] = -20
            if (otherCiv.getAllyCiv() == civInfo.civName)
                modifierMap["Allied City-state"] = -20 // There had better be a DAMN good reason
        }

        for (city in otherCiv.cities) {
            val construction = city.cityConstructions.getCurrentConstruction()
            if (construction is Building && construction.hasUnique(UniqueType.TriggersCulturalVictory))
                modifierMap["About to win"] = 15
            if (construction is BaseUnit && construction.hasUnique(UniqueType.AddInCapital))
                modifierMap["About to win"] = 15
        }

        return modifierMap.values.sum()
    }


    private fun offerPeaceTreaty(civInfo: Civilization) {
        if (!civInfo.isAtWar() || civInfo.cities.isEmpty() || civInfo.diplomacy.isEmpty()) return

        val enemiesCiv = civInfo.diplomacy.filter { it.value.diplomaticStatus == DiplomaticStatus.War }
                .map { it.value.otherCiv() }
                .filterNot { it == civInfo || it.isBarbarian() || it.cities.isEmpty() }
                .filter { !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclinedPeace) }
                // Don't allow AIs to offer peace to city states allied with their enemies
                .filterNot { it.isCityState() && it.getAllyCiv() != null && civInfo.isAtWarWith(civInfo.gameInfo.getCivilization(it.getAllyCiv()!!)) }
                // ignore civs that we have already offered peace this turn as a counteroffer to another civ's peace offer
                .filter { it.tradeRequests.none { tradeRequest -> tradeRequest.requestingCiv == civInfo.civName && tradeRequest.trade.isPeaceTreaty() } }

        for (enemy in enemiesCiv) {
            val motivationToAttack = motivationToAttack(civInfo, enemy)
            if (motivationToAttack >= 10) continue // We can still fight. Refuse peace.

            // pay for peace
            val tradeLogic = TradeLogic(civInfo, enemy)

            tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeType.Treaty))
            tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeType.Treaty))

            var moneyWeNeedToPay = -TradeEvaluation().evaluatePeaceCostForThem(civInfo, enemy)

            if (civInfo.gold > 0 && moneyWeNeedToPay > 0) {
                if (moneyWeNeedToPay > civInfo.gold) {
                    moneyWeNeedToPay = civInfo.gold  // As much as possible
                }
                tradeLogic.currentTrade.ourOffers.add(
                    TradeOffer("Gold".tr(), TradeType.Gold, moneyWeNeedToPay)
                )
            }

            enemy.tradeRequests.add(TradeRequest(civInfo.civName, tradeLogic.currentTrade.reverse()))
        }
    }


    private fun automateUnits(civInfo: Civilization) {
        val sortedUnits = civInfo.units.getCivUnits().sortedBy { unit ->
            when {
                unit.baseUnit.isAirUnit() -> 2
                unit.baseUnit.isRanged() -> 3
                unit.baseUnit.isMelee() -> 4
                unit.isGreatPersonOfType("War") -> 5 // Generals move after military units
                else -> 1 // Civilian
            }
        }
        for (unit in sortedUnits) UnitAutomation.automateUnitMoves(unit)
    }

    private fun automateCityBombardment(civInfo: Civilization) {
        for (city in civInfo.cities) UnitAutomation.tryBombardEnemy(city)
    }

    private fun automateCities(civInfo: Civilization) {
        for (city in civInfo.cities) {
            if (city.isPuppet && city.population.population > 9
                    && !city.isInResistance()) {
                city.annexCity()
            }

            city.reassignAllPopulation()

            if (city.health < city.getMaxHealth()) {
                Automation.tryTrainMilitaryUnit(city) // need defenses if city is under attack
                if (city.cityConstructions.constructionQueue.isNotEmpty())
                    continue // found a unit to build so move on
            }

            city.cityConstructions.chooseNextConstruction()
        }
    }

    private fun trainSettler(civInfo: Civilization) {
        if (civInfo.isCityState()) return
        if (civInfo.isAtWar()) return // don't train settlers when you could be training troops.
        if (civInfo.wantsToFocusOn(Victory.Focus.Culture) && civInfo.cities.size > 3) return
        if (civInfo.cities.none() || civInfo.getHappiness() <= civInfo.cities.size + 5) return

        val settlerUnits = civInfo.gameInfo.ruleSet.units.values
                .filter { it.hasUnique(UniqueType.FoundCity) && it.isBuildable(civInfo) }
        if (settlerUnits.isEmpty()) return
        if (civInfo.units.getCivUnits().none { it.hasUnique(UniqueType.FoundCity) }
                && civInfo.cities.none {
                    val currentConstruction = it.cityConstructions.getCurrentConstruction()
                    currentConstruction is BaseUnit && currentConstruction.hasUnique(UniqueType.FoundCity)
                }) {

            val bestCity = civInfo.cities.filterNot { it.isPuppet }.maxByOrNull { it.cityStats.currentCityStats.production }!!
            if (bestCity.cityConstructions.builtBuildings.size > 1) // 2 buildings or more, otherwise focus on self first
                bestCity.cityConstructions.currentConstructionFromQueue = settlerUnits.minByOrNull { it.cost }!!.name
        }
    }

    // Technically, this function should also check for civs that have liberated one or more cities
    // However, that can be added in another update, this PR is large enough as it is.
    private fun tryVoteForDiplomaticVictory(civInfo: Civilization) {
        if (!civInfo.mayVoteForDiplomaticVictory()) return
        val chosenCiv: String? = if (civInfo.isMajorCiv()) {

            val knownMajorCivs = civInfo.getKnownCivs().filter { it.isMajorCiv() }
            val highestOpinion = knownMajorCivs
                .maxOfOrNull {
                    civInfo.getDiplomacyManager(it).opinionOfOtherCiv()
                }

            if (highestOpinion == null) null
            else knownMajorCivs.filter { civInfo.getDiplomacyManager(it).opinionOfOtherCiv() == highestOpinion}.random().civName

        } else {
            civInfo.getAllyCiv()
        }

        civInfo.diplomaticVoteForCiv(chosenCiv)
    }

    private fun issueRequests(civInfo: Civilization) {
        for (otherCiv in civInfo.getKnownCivs().filter { it.isMajorCiv() && !civInfo.isAtWarWith(it) }) {
            val diploManager = civInfo.getDiplomacyManager(otherCiv)
            if (diploManager.hasFlag(DiplomacyFlags.SettledCitiesNearUs))
                onCitySettledNearBorders(civInfo, otherCiv)
        }
    }

    private fun onCitySettledNearBorders(civInfo: Civilization, otherCiv: Civilization) {
        val diplomacyManager = civInfo.getDiplomacyManager(otherCiv)
        when {
            diplomacyManager.hasFlag(DiplomacyFlags.IgnoreThemSettlingNearUs) -> {
            }
            diplomacyManager.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs) -> {
                otherCiv.popupAlerts.add(PopupAlert(AlertType.CitySettledNearOtherCivDespiteOurPromise, civInfo.civName))
                diplomacyManager.setFlag(DiplomacyFlags.IgnoreThemSettlingNearUs, 100)
                diplomacyManager.setModifier(DiplomaticModifiers.BetrayedPromiseToNotSettleCitiesNearUs, -20f)
                diplomacyManager.removeFlag(DiplomacyFlags.AgreedToNotSettleNearUs)
            }
            else -> {
                val threatLevel = Automation.threatAssessment(civInfo, otherCiv)
                if (threatLevel < ThreatLevel.High) // don't piss them off for no reason please.
                    otherCiv.popupAlerts.add(PopupAlert(AlertType.DemandToStopSettlingCitiesNear, civInfo.civName))
            }
        }
        diplomacyManager.removeFlag(DiplomacyFlags.SettledCitiesNearUs)
    }

    /** Handle decision making after city conquest, namely whether the AI should liberate, puppet,
     * or raze a city */
    fun onConquerCity(civInfo: Civilization, city: City) {
        if (!city.hasDiplomaticMarriage()) {
            val foundingCiv = civInfo.gameInfo.getCivilization(city.foundingCiv)
            var valueAlliance = valueCityStateAlliance(civInfo, foundingCiv)
            if (civInfo.getHappiness() < 0)
                valueAlliance -= civInfo.getHappiness() // put extra weight on liberating if unhappy
            if (foundingCiv.isCityState() && city.civ != civInfo && foundingCiv != civInfo
                    && !civInfo.isAtWarWith(foundingCiv)
                    && valueAlliance > 0) {
                city.liberateCity(civInfo)
                return
            }
        }

        city.puppetCity(civInfo)
        if ((city.population.population < 4 || civInfo.isCityState())
                && city.foundingCiv != civInfo.civName && city.canBeDestroyed(justCaptured = true)) {
            // raze if attacker is a city state
            city.annexCity()
            city.isBeingRazed = true
        }
    }

    fun getMinDistanceBetweenCities(civ1: Civilization, civ2: Civilization): Int {
        return getClosestCities(civ1, civ2)?.aerialDistance ?: Int.MAX_VALUE
    }

    data class CityDistance(val city1: City, val city2: City, val aerialDistance: Int)

    fun getClosestCities(civ1: Civilization, civ2: Civilization): CityDistance? {
        if (civ1.cities.isEmpty() || civ2.cities.isEmpty())
            return null

        val cityDistances = arrayListOf<CityDistance>()
        for (civ1city in civ1.cities)
            for (civ2city in civ2.cities)
                cityDistances += CityDistance(civ1city, civ2city,
                        civ1city.getCenterTile().aerialDistanceTo(civ2city.getCenterTile()))

        return cityDistances.minByOrNull { it.aerialDistance }!!
    }
}
