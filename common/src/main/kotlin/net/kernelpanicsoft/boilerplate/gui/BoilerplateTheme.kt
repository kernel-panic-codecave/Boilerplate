package net.kernelpanicsoft.boilerplate.gui

import androidx.compose.runtime.Composable
import net.kernelpanicsoft.archie.gui.theme.Theme

@Composable
inline fun BoilerplateTheme(crossinline content: @Composable () -> Unit)
{
	Theme {
		content()
	}
}