package com.unciv.ui.utils

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.Drawable

open class BorderedTable(
    val path: String = "",
    defaultBgShape: String = BaseScreen.skinStrings.rectangleWithOutlineShape,
    defaultBgBorder: String = BaseScreen.skinStrings.rectangleWithOutlineShape) : Table() {

    var bgColor: Color = Color.BLACK
    var bgBorderColor: Color = Color.WHITE

    var borderSize: Float = 5f
    var borderOnTop: Boolean = false

    private var bgInner: Drawable = BaseScreen.skinStrings.getUiBackground(path, defaultBgShape)
    private var bgBorder: Drawable = BaseScreen.skinStrings.getUiBackground(path + "Border", defaultBgBorder)

    override fun drawBackground(batch: Batch, parentAlpha: Float, x: Float, y: Float) {
        if (borderOnTop) {
            batch.setColor(
                bgColor.r*color.r,
                bgColor.g*color.g,
                bgColor.b*color.b,
                bgColor.a*color.a * parentAlpha)
            bgInner.draw(batch, x, y, width, height)
            batch.setColor(
                bgBorderColor.r*color.r,
                bgBorderColor.g*color.g,
                bgBorderColor.b*color.b,
                bgBorderColor.a*color.a * parentAlpha)
            bgBorder.draw(batch, x-borderSize/2, y-borderSize/2, width+borderSize, height+borderSize)
        } else {
            batch.setColor(
                bgBorderColor.r*color.r,
                bgBorderColor.g*color.g,
                bgBorderColor.b*color.b,
                bgBorderColor.a*color.a * parentAlpha)
            bgBorder.draw(batch, x-borderSize/2, y-borderSize/2, width+borderSize, height+borderSize)
            batch.setColor(
                bgColor.r*color.r,
                bgColor.g*color.g,
                bgColor.b*color.b,
                bgColor.a*color.a * parentAlpha)
            bgInner.draw(batch, x, y, width, height)
        }
    }

}
