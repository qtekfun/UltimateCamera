package com.qtekfun.ultimatecamera.core.luts

import android.content.Context
import com.qtekfun.ultimatecamera.core.luts.catalogue.CatalogueLutDownloader
import com.qtekfun.ultimatecamera.core.luts.catalogue.DefaultCatalogueLutDownloader
import com.qtekfun.ultimatecamera.core.luts.catalogue.LutCatalogue
import java.io.File

/**
 * The app's single [LutLibrary] (in `filesDir/luts`), shared by the camera and the library screen: two instances on the
 * same directory would each cache their own index. Downloads work in `cacheDir/lut-downloads` (deleted after each one).
 */
object LutLibraries {
    @Volatile
    private var library: FileLutLibrary? = null

    @Volatile
    private var catalogue: LutCatalogue? = null

    fun library(context: Context): LutLibrary = library ?: synchronized(this) {
        library ?: FileLutLibrary(File(context.applicationContext.filesDir, "luts")).also { library = it }
    }

    /** The makers' catalogue shipped in the APK (REQ-COL-006). Parsed once. */
    fun catalogue(context: Context): LutCatalogue = catalogue ?: synchronized(this) {
        catalogue ?: LutCatalogue.fromAssets(context.applicationContext.assets).also { catalogue = it }
    }

    fun downloader(context: Context): CatalogueLutDownloader =
        DefaultCatalogueLutDownloader(library(context), File(context.applicationContext.cacheDir, "lut-downloads"))
}
