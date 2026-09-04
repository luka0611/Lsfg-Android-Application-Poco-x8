package com.lsfg.android.session

import android.system.Os
import android.util.Log

/**
 * Loads classes that live in system_server's own jars rather than the framework an app
 * process gets — `com.android.server.display.DisplayControl` above all, which has owned
 * `getPhysicalDisplayToken` since Android 14 moved it off `SurfaceControl`.
 *
 * Two things make this work that a plain `PathClassLoader` plus `System.loadLibrary` does
 * not, and both were learned the hard way on this device:
 *
 *  1. **The loader must share the linker namespace.** `ClassLoaderFactory.createClassLoader`
 *     with `isNamespaceShared = true` is what lets a native library loaded through it
 *     resolve against system_server's namespace. A `PathClassLoader` gets its own.
 *
 *  2. **The native library must be loaded into *this* loader's context.** `libandroid_servers.so`
 *     registers its JNI in `JNI_OnLoad` by looking the class up through the class loader
 *     associated with the load. `System.loadLibrary` associates it with the *caller's*
 *     loader — the app's — so the registration binds to a `DisplayControl` that is not the
 *     one we hold, the methods resolve, and every call then fails at the native boundary.
 *     `Runtime.loadLibrary0(Class, String)` takes the class explicitly and picks the right
 *     loader. This is the same approach scrcpy uses (Genymobile/scrcpy#4446).
 *
 * Also reads `SYSTEMSERVERCLASSPATH` rather than hardcoding `/system/framework/services.jar`:
 * on recent releases the system server classpath spans several jars, some under /apex, and
 * a class loaded from the wrong one may not resolve its dependencies.
 */
internal object SystemServerClasses {

    private const val TAG = "SystemServerClasses"
    private const val FALLBACK_CLASSPATH = "/system/framework/services.jar"

    private val loader: ClassLoader? by lazy { createLoader() }

    /** Class name -> loaded class. Null means "tried and failed"; absent means "not tried". */
    private val cache = HashMap<String, Class<*>?>()

    /**
     * Load [className] from the system server classpath, or return null if unavailable.
     * When [withNativeLibrary] is set, that library is loaded into the returned class's
     * loader context so its JNI registration binds to this class — see (2) above.
     */
    @Synchronized
    fun load(className: String, withNativeLibrary: String? = null): Class<*>? {
        cache[className]?.let { return it }
        if (cache.containsKey(className)) return null

        val result = runCatching {
            val cls = (loader ?: error("no system server class loader")).loadClass(className)
            if (withNativeLibrary != null) loadLibraryInto(cls, withNativeLibrary)
            cls
        }.onFailure {
            Log.w(TAG, "Could not load $className from the system server classpath", it)
        }.getOrNull()

        cache[className] = result
        return result
    }

    /** Describes what this object managed to set up, for the capture-failure message. */
    @Synchronized
    fun describe(): String {
        val env = systemServerClasspathFromEnv()
        val source = if (env != null) "env(${env.split(':').size} jars)" else "fallback"
        return "sysserver-classpath=$source loader=${if (loader != null) "ok" else "FAILED"}"
    }

    private fun systemServerClasspathFromEnv(): String? =
        runCatching { Os.getenv("SYSTEMSERVERCLASSPATH") }.getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private fun systemServerClasspath(): String =
        systemServerClasspathFromEnv() ?: FALLBACK_CLASSPATH

    private fun createLoader(): ClassLoader? = runCatching {
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val create = factory.getDeclaredMethod(
            "createClassLoader",
            String::class.java,   // dexPath
            String::class.java,   // librarySearchPath
            String::class.java,   // libraryPermittedPath
            ClassLoader::class.java,
            Int::class.javaPrimitiveType,   // targetSdkVersion
            Boolean::class.javaPrimitiveType, // isNamespaceShared
            String::class.java,   // classLoaderName
        )
        create.invoke(
            null,
            systemServerClasspath(),
            null,
            null,
            ClassLoader.getSystemClassLoader(),
            0,
            true,
            null,
        ) as ClassLoader
    }.onFailure {
        Log.w(TAG, "ClassLoaderFactory.createClassLoader unavailable", it)
    }.getOrNull()

    private fun loadLibraryInto(owner: Class<*>, library: String) {
        val load = Runtime::class.java.getDeclaredMethod(
            "loadLibrary0", Class::class.java, String::class.java,
        )
        load.isAccessible = true
        load.invoke(Runtime.getRuntime(), owner, library)
    }
}
