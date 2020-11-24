package pl.allegro.tech.servicemesh.envoycontrol.permissions

import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo1EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.Echo2EnvoyAuthConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoHeadersContainer
import pl.allegro.tech.servicemesh.envoycontrol.config.service.GenericServiceExtension
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.toDuration

class ClientNameTrustedHeaderTest {
    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()


        /**
         * TODO(mf): dodaj jawnie propertiesy dotyczące tematu tutaj
         */
        @JvmField
        @RegisterExtension
        @Order(Order.DEFAULT - 1)
        val envoyControl = EnvoyControlExtension(consul)

        @JvmField
        @RegisterExtension
        val service = GenericServiceExtension(EchoHeadersContainer())

        // language=yaml
        private var proxySettings = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies: []
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy = EnvoyExtension(envoyControl, service,
            Echo1EnvoyAuthConfig.copy(configOverride = proxySettings)
        )

        // language=yaml
        private val echoClientsConfig = """
            node:
              metadata:
                proxy_settings:
                  outgoing:
                    dependencies:
                      - service: "echo"
        """.trimIndent()

        @JvmField
        @RegisterExtension
        val envoy2 = EnvoyExtension(envoyControl, service, Echo2EnvoyAuthConfig.copy(configOverride = echoClientsConfig))

        val echo4EnvoyAuthConfig = Echo1EnvoyAuthConfig.copy(
            serviceName = "echo4",
            certificateChain = "/app/fullchain_echo4.pem",
            privateKey = "/app/privkey_echo4.pem",
            configOverride = echoClientsConfig
        )

        @JvmField
        @RegisterExtension
        val envoy4MultipleSANs = EnvoyExtension(envoyControl, service, echo4EnvoyAuthConfig)

        val echo5EnvoyAuthConfig = Echo1EnvoyAuthConfig.copy(
            serviceName = "echo5",
            certificateChain = "/app/fullchain_echo5.pem",
            privateKey = "/app/privkey_echo5.pem",
            configOverride = echoClientsConfig
        )

        @JvmField
        @RegisterExtension
        val envoy5InvalidSANs = EnvoyExtension(envoyControl, service, echo5EnvoyAuthConfig)
    }

    @BeforeEach
    fun beforeEach() {
        consul.server.operations.registerService(
            id = "echo",
            name = "echo",
            address = envoy.container.ipAddress(),
            port = EnvoyContainer.INGRESS_LISTENER_CONTAINER_PORT,
            tags = listOf("mtls:enabled")
        )
        waitForEnvoysInitialized()
    }

    private fun waitForEnvoysInitialized() {
        untilAsserted(wait = Duration.ofSeconds(20)) {
            assertThat(envoy2.container.admin().isEndpointHealthy("echo", envoy.container.ipAddress())).isTrue()
            assertThat(envoy4MultipleSANs.container.admin().isEndpointHealthy("echo", envoy.container.ipAddress())).isTrue()
            assertThat(envoy5InvalidSANs.container.admin().isEndpointHealthy("echo", envoy.container.ipAddress())).isTrue()
        }
    }

    @Test
    fun `should always remove "x-client-name-trusted" header on every envoy ingress request`() {
        // when
        val response = envoy2.ingressOperations.callLocalService(
            "/log-unlisted-clients",
            Headers.of(mapOf("x-client-name-trusted" to "fake-service"))
        )
        // then
        assertThat(response.header("x-client-name-trusted")).isNull()

    }

    @Test
    fun `should add "x-client-name-trusted" header on envoy ingress request`() {
        // when
        val response = envoy2.egressOperations.callService("echo", emptyMap(), "/log-unlisted-clients")
        // then
        assertThat(response.header("x-client-name-trusted")).isEqualTo("echo2")
    }

    @Test
    fun `should override "x-client-name-trusted" header with trusted client name form certificate on request`() {
        // when
        val headers = mapOf("x-client-name-trusted" to "fake-service")
        val response = envoy2.egressOperations.callService("echo", headers, "/log-unlisted-clients")
        // then
        assertThat(response.header("x-client-name-trusted")).isEqualTo("echo2")
    }

    @Test
    fun `should set "x-client-name-trusted" header based on all URIs in certificate SAN field`() {
        // when
        val response = envoy4MultipleSANs.egressOperations.callService("echo", emptyMap(), "/log-unlisted-clients")
        // then
        assertThat(response.header("x-client-name-trusted")).isEqualTo("echo4,echo4-special,echo4-admin")
    }

    /**
     * TODO(mf): ten test  powinien działać odwrotnie - client name nie powinien być dodany, bo SAN jest w złym formacie
     */
    @Test
    fun `should set "x-client-name-trusted" header based on URIs in certificate SAN field regardles protocol used in SAN alt name`() {
        // when
        val response = envoy5InvalidSANs.egressOperations.callService("echo", emptyMap(), "/log-unlisted-clients")
        // then
        assertThat(response.header("x-client-name-trusted")).isEqualTo("echo5,echo5-special,echo5-admin")
    }


    /**
     * TODO(mf). Add tests:
     * * client cert signed by invalid CA
     * * client cert with slightly different SAN
     */
}