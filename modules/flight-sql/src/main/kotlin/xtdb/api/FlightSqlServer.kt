package xtdb.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xtdb.api.module.XtdbModule
import xtdb.util.requiringResolve

private val OPEN_SERVER = requiringResolve("xtdb.flight-sql", "open-server")

object FlightSqlServer {
    @JvmStatic
    fun flightSqlServer() = Factory()

    @SerialName("!FlightSqlServer")
    @Serializable
    data class Factory(
        var host: String = "127.0.0.1",
        var port: Int = 9832
    ) : XtdbModule.Factory {
        override val moduleKey = "xtdb.flight-sql-server"

        fun host(host: String) = apply { this.host = host }
        fun port(port: Int) = apply { this.port = port }

        override fun openModule(xtdb: IXtdb) = OPEN_SERVER(xtdb, this) as xtdb.api.module.XtdbModule
    }

    class Registration: XtdbModule.Registration {
        override fun register(registry: XtdbModule.Registry) {
            registry.registerModuleFactory(Factory::class)
        }
    }
}

@JvmSynthetic
fun Xtdb.Config.flightSqlServer(configure: FlightSqlServer.Factory.() -> Unit = {}) {
    modules(FlightSqlServer.Factory().also(configure))
}
