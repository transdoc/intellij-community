package circlet.settings

import circlet.auth.*
import circlet.client.*
import circlet.client.api.*
import circlet.components.*
import circlet.messages.*
import circlet.platform.api.oauth.*
import circlet.platform.client.*
import circlet.workspaces.*
import com.intellij.openapi.options.*
import com.intellij.openapi.ui.*
import com.intellij.openapi.wm.*
import com.intellij.ui.*
import klogging.*
import runtime.*
import runtime.async.*
import runtime.reactive.*
import java.awt.*
import javax.swing.*
import javax.swing.Action.*

val log = logger<CircletConfigurable>()

sealed class LoginState(val server: String) {
    class Disconnected(server: String, val error: String?) : LoginState(server)
    class Connected(server: String, val workspaceState: WorkspaceState) : LoginState(server)
    class Connecting(server: String, val lt: LifetimeSource) : LoginState(server)
}

class CircletConfigurable : SearchableConfigurable {

    val uiLifetime = LifetimeSource()

    var state = mutableProperty<LoginState>(LoginState.Disconnected("", null))

    var intendedSettings: CircletServerSettings = circletSettings.settings.value

    private val panel = JPanel(FlowLayout())

    init {
        state.forEach(uiLifetime) { st ->
            panel.removeAll()
            when (st) {
                is LoginState.Disconnected -> {
                    val server = JTextField(20).apply {
                        text = "http://localhost:8000"
                    }
                    panel.add(
                        JPanel(FlowLayout()).apply {
                            add(JLabel().apply {
                                text = "Server:"
                            })
                            add(server)
                            val connectButton = JButton("Connect")
                            add(connectButton.apply {
                                addActionListener {
                                    connectButton.isEnabled = false
                                    val servername = server.text
                                    intendedSettings = CircletServerSettings(true, servername)
                                    connect(servername, { wsConfig, lt -> authTokenInteractive(lt, wsConfig) }, false)
                                }
                            })
                            if (st.error != null) {
                                add(JLabel().apply {
                                    text = st.error
                                })
                            }
                        }
                    )
                }
                is LoginState.Connecting -> {
                    panel.add(
                        JPanel(FlowLayout()).apply {
                            add(JLabel("Connection to ${st.server}..."))
                            val connectButton = JButton("Cancel")
                            add(connectButton.apply {
                                addActionListener {
                                    st.lt.terminate()
                                    state.value = LoginState.Disconnected(st.server, null)
                                    intendedSettings = CircletServerSettings(false, st.server)
                                }
                            })
                        }
                    )
                }
                is LoginState.Connected -> {
                    panel.add(
                        JPanel(FlowLayout()).apply {
                            add(JLabel("Connected to ${st.server} as ${st.workspaceState.profile.englishFullName()}"))
                            val connectButton = JButton("Disconnect")
                            add(connectButton.apply {
                                addActionListener {
                                    state.value = LoginState.Disconnected(st.server, null)
                                    intendedSettings = CircletServerSettings(false, st.server)
                                }
                            })
                        }
                    )
                }
            }
            panel.revalidate()
            panel.validate()
            panel.invalidate()
        }
    }

    private fun connect(servername: String, getToken: suspend (wsConfig: WorkspaceConfiguration, Lifetime: Lifetime) -> OAuthTokenResponse, expectErrorToken: Boolean) {
        launch(uiLifetime, Ui) {
            val connectLt = uiLifetime.nested()
            try {
                try {
                    state.value = LoginState.Connecting(servername, connectLt)

                    val wsConfig = ideaConfig(servername)
                    val ps = InMemoryPersistence()
                    val accessToken = getToken(wsConfig, connectLt)

                    when (accessToken) {
                        is OAuthTokenResponse.Success -> {
                            connectLt.using { probleLt ->
                                val client = KCircletClient(probleLt, wsConfig.server, ps, PersistenceConfiguration.nothing)
                                client.start(accessToken.toTokenInfo())
                                val nState = client.me.info().toWorkspaceState(client.token)
                                intendedSettings = CircletServerSettings(true, servername)
                                state.value = LoginState.Connected(servername, nState)
                            }
                        }
                        is OAuthTokenResponse.Error -> {
                            if (expectErrorToken) {
                                state.value = LoginState.Disconnected(servername, "")
                            }
                            else {
                                state.value = LoginState.Disconnected(servername, accessToken.description)
                            }
                        }
                    }
                }
                catch (th: Throwable) {
                    log.error(th)
                    state.value = LoginState.Disconnected(servername, th.message ?: "error of type ${th.javaClass.simpleName}")
                }
                val frame = SwingUtilities.getAncestorOfClass(JFrame::class.java, panel)
                AppIcon.getInstance().requestFocus(frame as IdeFrame?)
            }
            finally {
                connectLt.terminate()
            }
        }
    }

    override fun isModified(): Boolean {
        val stateFromSettings = circletSettings.settings.value
        return intendedSettings.server != stateFromSettings.server || intendedSettings.enabled != stateFromSettings.enabled
    }

    override fun getId(): String = "circlet.settings.connection"

    override fun getDisplayName(): String = CircletBundle.message("connection-configurable.display-name")

    override fun apply() {
        launch(Lifetime.Eternal, Ui) {
            log.info { "Apply configurable" }
            val st = state.value

            // 1. save workspace settings
            circletSettings.applySettings(intendedSettings)

            // 2. apply login information
            val state = if (st is LoginState.Connected) st.workspaceState else null

            circletWorkspace.applyState(state)
        }
    }

    override fun reset() {
        val st = circletSettings.settings.value
        if (st.enabled) {
            connect(st.server, { wsConfig, lifetime -> appAuth(wsConfig) }, true)
        }
        else {
            state.value = LoginState.Disconnected(st.server, null)
        }
    }

    private suspend fun appAuth(wsConfig: WorkspaceConfiguration): OAuthTokenResponse {
        // todo: handle nulls.
        val workspaces = circletWorkspace.workspaces.value ?: error("workspaces is null")
        val state = workspaces.workspaceStateFromPersistence() ?: error("state from persistence is null")
        val refreshToken = state.token.refreshToken ?: error("refresh token is null")
        return wsConfig.refreshTokenFlow(refreshToken)
    }

    override fun createComponent(): JComponent? = panel

    override fun disposeUIResources() {
        uiLifetime.terminate()
    }
}

data class CircletServerSettings(
    var enabled: Boolean = false,
    var server: String = ""
)

class LoginDialogWrapper : DialogWrapper(true) {

    val server = JTextField(20).apply {
        text = "http://localhost:8000"
    }

    init {
        init()
        myOKAction.putValue(NAME, "Connect")
        title = "Circlet"
    }

    override fun createCenterPanel(): JComponent? {
        return JPanel(FlowLayout()).apply {
            add(JLabel().apply {
                text = "Server:"
            })
            add(server)
            pack()
        }
    }
}

