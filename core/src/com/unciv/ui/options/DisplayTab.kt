package com.unciv.ui.options

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Array
import com.unciv.UncivGame
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.ScreenSize
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.models.translations.tr
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.newgamescreen.TranslatedSelectBox
import com.unciv.ui.popup.ConfirmPopup
import com.unciv.ui.utils.BaseScreen
import com.unciv.ui.utils.UncivSlider
import com.unciv.ui.utils.WrappableLabel
import com.unciv.ui.utils.extensions.brighten
import com.unciv.ui.utils.extensions.onChange
import com.unciv.ui.utils.extensions.onClick
import com.unciv.ui.utils.extensions.toLabel
import com.unciv.ui.utils.extensions.toTextButton

private val resolutionArray = com.badlogic.gdx.utils.Array(arrayOf("750x500", "900x600", "1050x700", "1200x800", "1500x1000"))

fun displayTab(
    optionsPopup: OptionsPopup,
    onChange: () -> Unit,
) = Table(BaseScreen.skin).apply {
    pad(10f)
    defaults().pad(2.5f)

    val settings = optionsPopup.settings

    optionsPopup.addCheckbox(this, "Show unit movement arrows", settings.showUnitMovements, true) { settings.showUnitMovements = it }
    optionsPopup.addCheckbox(this, "Show tile yields", settings.showTileYields, true) { settings.showTileYields = it } // JN
    optionsPopup.addCheckbox(this, "Show worked tiles", settings.showWorkedTiles, true) { settings.showWorkedTiles = it }
    optionsPopup.addCheckbox(this, "Show resources and improvements", settings.showResourcesAndImprovements, true) { settings.showResourcesAndImprovements = it }
    optionsPopup.addCheckbox(this, "Show tutorials", settings.showTutorials, true, false) { settings.showTutorials = it }
    addResetTutorials(this, settings)
    optionsPopup.addCheckbox(this, "Show pixel improvements", settings.showPixelImprovements, true) { settings.showPixelImprovements = it }
    optionsPopup.addCheckbox(this, "Experimental Demographics scoreboard", settings.useDemographics, true) { settings.useDemographics = it }
    optionsPopup.addCheckbox(this, "Show zoom buttons in world screen", settings.showZoomButtons, true) { settings.showZoomButtons = it }

    addMinimapSizeSlider(this, settings, optionsPopup.selectBoxMinWidth)

    addUnitIconAlphaSlider(this, settings, optionsPopup.selectBoxMinWidth)

    addScreenSizeSelectBox(this, settings, optionsPopup.selectBoxMinWidth, onChange)

    addTileSetSelectBox(this, settings, optionsPopup.selectBoxMinWidth, onChange)

    addUnitSetSelectBox(this, settings, optionsPopup.selectBoxMinWidth, onChange)

    addSkinSelectBox(this, settings, optionsPopup.selectBoxMinWidth, onChange)

    optionsPopup.addCheckbox(this, "Continuous rendering", settings.continuousRendering) {
        settings.continuousRendering = it
        Gdx.graphics.isContinuousRendering = it
    }

    val continuousRenderingDescription = "When disabled, saves battery life but certain animations will be suspended"
    val continuousRenderingLabel = WrappableLabel(
        continuousRenderingDescription,
        optionsPopup.tabs.prefWidth, Color.ORANGE.brighten(0.7f), 14
    )
    continuousRenderingLabel.wrap = true
    add(continuousRenderingLabel).colspan(2).padTop(10f).row()

}

private fun addMinimapSizeSlider(table: Table, settings: GameSettings, selectBoxMinWidth: Float) {
    table.add("Minimap size".toLabel()).left().fillX()

    // The meaning of the values needs a formula to be synchronized between here and
    // [Minimap.init]. It goes off-10%-11%..29%-30%-35%-40%-45%-50% - and the percentages
    // correspond roughly to the minimap's proportion relative to screen dimensions.
    val offTranslated = "off".tr()  // translate only once and cache in closure
    val getTipText: (Float) -> String = {
        when (it) {
            0f -> offTranslated
            in 0.99f..21.01f -> "%.0f".format(it + 9) + "%"
            else -> "%.0f".format(it * 5 - 75) + "%"
        }
    }
    val minimapSlider = UncivSlider(
        0f, 25f, 1f,
        initial = if (settings.showMinimap) settings.minimapSize.toFloat() else 0f,
        getTipText = getTipText
    ) {
        val size = it.toInt()
        if (size == 0) settings.showMinimap = false
        else {
            settings.showMinimap = true
            settings.minimapSize = size
        }
        settings.save()
        val worldScreen = UncivGame.Current.getWorldScreenIfActive()
        if (worldScreen != null)
            worldScreen.shouldUpdate = true
    }
    table.add(minimapSlider).minWidth(selectBoxMinWidth).pad(10f).row()
}

private fun addUnitIconAlphaSlider(table: Table, settings: GameSettings, selectBoxMinWidth: Float) {
    table.add("Unit icon opacity".toLabel()).left().fillX()

    val getTipText: (Float) -> String = {"%.0f".format(it*100) + "%"}

    val unitIconAlphaSlider = UncivSlider(
        0f, 1f, 0.1f, initial = settings.unitIconOpacity, getTipText = getTipText
    ) {
        settings.unitIconOpacity = it
        settings.save()

        val worldScreen = UncivGame.Current.getWorldScreenIfActive()
        if (worldScreen != null)
            worldScreen.shouldUpdate = true

    }
    table.add(unitIconAlphaSlider).minWidth(selectBoxMinWidth).pad(10f).row()
}

private fun addScreenSizeSelectBox(table: Table, settings: GameSettings, selectBoxMinWidth: Float, onResolutionChange: () -> Unit) {
    table.add("Screen Size".toLabel()).left().fillX()

    val screenSizeSelectBox = TranslatedSelectBox(ScreenSize.values().map { it.name }, settings.screenSize.name,table.skin)
    table.add(screenSizeSelectBox).minWidth(selectBoxMinWidth).pad(10f).row()

    screenSizeSelectBox.onChange {
        settings.screenSize = ScreenSize.valueOf(screenSizeSelectBox.selected.value)
        onResolutionChange()
    }
}

private fun addTileSetSelectBox(table: Table, settings: GameSettings, selectBoxMinWidth: Float, onTilesetChange: () -> Unit) {
    table.add("Tileset".toLabel()).left().fillX()

    val tileSetSelectBox = SelectBox<String>(table.skin)
    val tileSetArray = Array<String>()
    val tileSets = ImageGetter.getAvailableTilesets()
    for (tileset in tileSets) tileSetArray.add(tileset)
    tileSetSelectBox.items = tileSetArray
    tileSetSelectBox.selected = settings.tileSet
    table.add(tileSetSelectBox).minWidth(selectBoxMinWidth).pad(10f).row()

    val unitSets = ImageGetter.getAvailableUnitsets()

    tileSetSelectBox.onChange {
        // Switch unitSet together with tileSet as long as one with the same name exists and both are selected
        if (settings.tileSet == settings.unitSet && unitSets.contains(tileSetSelectBox.selected)) {
            settings.unitSet = tileSetSelectBox.selected
        }
        settings.tileSet = tileSetSelectBox.selected
        // ImageGetter ruleset should be correct no matter what screen we're on
        TileSetCache.assembleTileSetConfigs(ImageGetter.ruleset.mods)
        onTilesetChange()
    }
}

private fun addUnitSetSelectBox(table: Table, settings: GameSettings, selectBoxMinWidth: Float, onUnitsetChange: () -> Unit) {
    table.add("Unitset".toLabel()).left().fillX()

    val unitSetSelectBox = SelectBox<String>(table.skin)
    val unitSetArray = Array<String>()
    val nullValue = "None".tr()
    unitSetArray.add(nullValue)
    val unitSets = ImageGetter.getAvailableUnitsets()
    for (unitset in unitSets) unitSetArray.add(unitset)
    unitSetSelectBox.items = unitSetArray
    unitSetSelectBox.selected = settings.unitSet ?: nullValue
    table.add(unitSetSelectBox).minWidth(selectBoxMinWidth).pad(10f).row()

    unitSetSelectBox.onChange {
        settings.unitSet = if (unitSetSelectBox.selected != nullValue) unitSetSelectBox.selected else null
        // ImageGetter ruleset should be correct no matter what screen we're on
        TileSetCache.assembleTileSetConfigs(ImageGetter.ruleset.mods)
        onUnitsetChange()
    }
}

private fun addSkinSelectBox(table: Table, settings: GameSettings, selectBoxMinWidth: Float, onSkinChange: () -> Unit) {
    table.add("UI Skin".toLabel()).left().fillX()

    val skinSelectBox = SelectBox<String>(table.skin)
    val skinArray = Array<String>()
    val skins = ImageGetter.getAvailableSkins()
    for (skin in skins) skinArray.add(skin)
    skinSelectBox.items = skinArray
    skinSelectBox.selected = settings.skin
    table.add(skinSelectBox).minWidth(selectBoxMinWidth).pad(10f).row()

    skinSelectBox.onChange {
        settings.skin = skinSelectBox.selected
        // ImageGetter ruleset should be correct no matter what screen we're on
        SkinCache.assembleSkinConfigs(ImageGetter.ruleset.mods)
        onSkinChange()
    }
}

private fun addResetTutorials(table: Table, settings: GameSettings) {
    val resetTutorialsButton = "Reset tutorials".toTextButton()
	resetTutorialsButton.onClick {
            ConfirmPopup(
                table.stage,
                "Do you want to reset completed tutorials?",
                "Reset"
            ) {
                settings.tutorialsShown.clear()
                settings.tutorialTasksCompleted.clear()
                settings.save()
                resetTutorialsButton.setText("Done!".tr())
                resetTutorialsButton.clearListeners()
            }.open(true)
    }
    table.add(resetTutorialsButton).center().row()
}
