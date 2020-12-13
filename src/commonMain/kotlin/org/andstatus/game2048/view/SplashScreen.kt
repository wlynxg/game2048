package org.andstatus.game2048.view

import com.soywiz.korge.view.Container
import com.soywiz.korge.view.Stage
import com.soywiz.korge.view.container
import com.soywiz.korge.view.image
import com.soywiz.korge.view.position
import com.soywiz.korge.view.size
import com.soywiz.korim.format.readBitmap
import com.soywiz.korio.file.std.resourcesVfs
import com.soywiz.korui.layout.MathEx

suspend fun Stage.splashScreen(colorThemeEnum: ColorThemeEnum): Container = container {
    val suffix = if (colorThemeEnum == ColorThemeEnum.DARK) "dark" else "light"
    image(resourcesVfs["assets/splash_$suffix.png"].readBitmap()) {
        val size1 = MathEx.min(views.virtualWidth, views.virtualHeight) * 0.4
        size(size1, size1)
        position((views.virtualWidth - size1) / 2, (views.virtualHeight - size1) / 2)
    }
}
