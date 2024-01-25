package xtdb.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xtdb.util.requiringResolve
import xtdb.api.module.XtdbModule

object HttpServer {
    @JvmStatic
    fun httpServer() = Factory()

    private val OPEN_SERVER = requiringResolve("xtdb.server", "open-server")

    @Serializable
    @SerialName("!HttpServer")
    data class Factory(
        var port: Int = 3000,
        var readOnly: Boolean = false,
    ) : XtdbModule.Factory {
        override val moduleKey = "xtdb.http-server"

        fun port(port: Int) = apply { this.port = port }
        fun readOnly(readOnly: Boolean) = apply { this.readOnly = readOnly }

        override fun openModule(xtdb: IXtdb) = OPEN_SERVER(xtdb, this) as XtdbModule
    }

    /**
     * @suppress
     */
    class Registration : XtdbModule.Registration {
        override fun register(registry: XtdbModule.Registry) {
            registry.registerModuleFactory(Factory::class)
        }
    }
}

@JvmSynthetic
fun Xtdb.Config.httpServer(configure: HttpServer.Factory.() -> Unit = {}) {
    modules(HttpServer.Factory().also(configure))
}
