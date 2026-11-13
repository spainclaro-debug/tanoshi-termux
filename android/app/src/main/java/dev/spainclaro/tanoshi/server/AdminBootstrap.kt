package dev.spainclaro.tanoshi.server

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * First-run admin bootstrap over Tanoshi's GraphQL API.
 *
 * Tanoshi only exposes the `serverStatus` query and the `register` mutation
 * without a JWT, so this is the only safe way for the app to create the very
 * first (admin) account. Everything runs over loopback HTTP against the server
 * the foreground service just started; no third party HTTP stack is used.
 *
 * The server returns HTTP 200 with a top-level `errors` array for GraphQL
 * failures, so both HTTP status and body are inspected.
 */
object AdminBootstrap {

    private const val TAG = "AdminBootstrap"

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Tanoshi rejects passwords shorter than this (argon2 user service). */
    const val MIN_PASSWORD_LENGTH = 8

    private const val SERVER_STATUS_QUERY =
        "query ServerStatus { serverStatus { activated version } }"

    private const val REGISTER_MUTATION =
        "mutation Register(\$login: LoginInput!, \$isAdmin: Boolean!) { " +
            "register(login: \$login, isAdmin: \$isAdmin) }"

    /**
     * Queries `serverStatus.activated`.
     *
     * Returns the server's answer; throws (with a user readable message) when
     * the status cannot be determined because the server is unreachable or
     * answered with a GraphQL error. Callers that only care about "should the
     * onboarding dialog be shown?" are expected to treat such a failure as
     * "unknown" rather than "not activated".
     */
    suspend fun isActivated(port: Int): Boolean = withContext(Dispatchers.IO) {
        queryIsActivated(port)
    }

    /**
     * Registers the first admin account. Fails fast for obviously invalid
     * credentials and otherwise returns the GraphQL/HTTP error message so the
     * UI can show something actionable.
     *
     * With Tanoshi's schema this mutation only succeeds while the server has no
     * users; if the server was already activated in the browser meanwhile, the
     * call reports success (the admin account the user needs already exists).
     */
    suspend fun createAdmin(port: Int, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUsername = username.trim()
                when {
                    cleanUsername.isEmpty() ->
                        return@withContext Result.failure(
                            IllegalArgumentException("Username must not be empty"),
                        )
                    cleanUsername.length > 64 ->
                        return@withContext Result.failure(
                            IllegalArgumentException("Username must be at most 64 characters"),
                        )
                    password.length < MIN_PASSWORD_LENGTH ->
                        return@withContext Result.failure(
                            IllegalArgumentException(
                                "Password must be at least $MIN_PASSWORD_LENGTH characters",
                            ),
                        )
                }

                val variables = JSONObject()
                    .put(
                        "login",
                        JSONObject()
                            .put("username", cleanUsername)
                            .put("password", password),
                    )
                    .put("isAdmin", true)
                val payload = JSONObject()
                    .put("query", REGISTER_MUTATION)
                    .put("variables", variables)
                    .toString()

                val response = postGraphQl(port, payload)
                val errors = response.optJSONArray("errors")
                if (errors != null && errors.length() > 0) {
                    val message = firstErrorMessage(errors)
                    // "Forbidden" means another user registered first: if the
                    // server is activated now, the admin account already exists.
                    if (message.contains("forbidden", ignoreCase = true) &&
                        runCatching { queryIsActivated(port) }.getOrDefault(false)
                    ) {
                        return@withContext Result.success(Unit)
                    }
                    return@withContext Result.failure(
                        AdminBootstrapException(
                            message.ifBlank { "The Tanoshi server rejected the admin registration" },
                        ),
                    )
                }

                val data = response.optJSONObject("data")
                if (data == null || data.isNull("register")) {
                    return@withContext Result.failure(
                        AdminBootstrapException(
                            "The Tanoshi server did not confirm the admin registration",
                        ),
                    )
                }
                Log.i(TAG, "Created the first Tanoshi admin account '$cleanUsername'")
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    // -----------------------------------------------------------------------
    // HTTP / JSON plumbing
    // -----------------------------------------------------------------------

    private fun queryIsActivated(port: Int): Boolean {
        val payload = JSONObject().put("query", SERVER_STATUS_QUERY).toString()
        val response = postGraphQl(port, payload)
        val errors = response.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            throw AdminBootstrapException(firstErrorMessage(errors))
        }
        val serverStatus = response.optJSONObject("data")?.optJSONObject("serverStatus")
            ?: throw AdminBootstrapException("The Tanoshi server returned no server status")
        return serverStatus.optBoolean("activated", false)
    }

    private fun postGraphQl(port: Int, payload: String): JSONObject {
        val endpoint = URL("http://127.0.0.1:$port/graphql")
        val connection = try {
            endpoint.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        } catch (e: IOException) {
            throw AdminBootstrapException(
                "Could not reach the Tanoshi server on port $port: ${e.describe()}",
            )
        }

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Connection", "close")

            connection.outputStream.use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (body.isBlank()) {
                throw AdminBootstrapException(
                    "The Tanoshi server returned an empty response (HTTP $statusCode)",
                )
            }
            return try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw AdminBootstrapException(
                    "The Tanoshi server returned an invalid response (HTTP $statusCode): " +
                        body.take(200),
                )
            }
        } catch (e: IOException) {
            throw AdminBootstrapException(
                "Could not reach the Tanoshi server on port $port: ${e.describe()}",
            )
        } finally {
            try {
                connection.disconnect()
            } catch (ignored: Throwable) {
                // Nothing to do.
            }
        }
    }

    private fun firstErrorMessage(errors: JSONArray): String {
        val messages = ArrayList<String>()
        for (index in 0 until errors.length()) {
            val message = errors.optJSONObject(index)?.optString("message").orEmpty()
            if (message.isNotBlank()) messages.add(message)
        }
        return messages.joinToString("; ").ifBlank { "Unknown GraphQL error" }
    }

    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    /** Failure surfaced to the UI; [message] is always user readable. */
    class AdminBootstrapException(message: String) : Exception(message)
}
