package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import io.envoyproxy.envoy.config.filter.http.lua.v2.Lua
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.IncomingPermissionsProperties

class LuaFilterFactory(private val incomingPermissionsProperties: IncomingPermissionsProperties) {

    private val ingressRbacLoggingScript: String = this::class.java.classLoader
        .getResource("lua/ingress_rbac_logging.lua")!!.readText()

    private val ingressRbacLoggingFilter: HttpFilter? = if (incomingPermissionsProperties.enabled) {
        HttpFilter.newBuilder()
            .setName("envoy.lua")
            .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode(ingressRbacLoggingScript).build()))
            .build()
    } else {
        null
    }

    fun ingressRbacLoggingFilter(group: Group): HttpFilter? =
        ingressRbacLoggingFilter.takeIf { group.proxySettings.incoming.permissionsEnabled }

    private val ingressClientScript: String = this::class.java.classLoader
            .getResource("lua/ingress_client.lua")!!.readText()

    private val ingressClientFilter: HttpFilter? = if (incomingPermissionsProperties.enabled) {
        HttpFilter.newBuilder()
                .setName("ingress.client.lua")
                .setTypedConfig(Any.pack(Lua.newBuilder().setInlineCode(ingressClientScript).build()))
                .build()
    } else {
        null
    }

    fun ingressClientFilter(group: Group): HttpFilter? = ingressClientFilter
}
