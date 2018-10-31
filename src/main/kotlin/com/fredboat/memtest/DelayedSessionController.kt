package com.fredboat.memtest

import com.neovisionaries.ws.client.OpeningHandshakeException
import net.dv8tion.jda.core.AccountType
import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.entities.impl.JDAImpl
import net.dv8tion.jda.core.exceptions.AccountTypeException
import net.dv8tion.jda.core.requests.Request
import net.dv8tion.jda.core.requests.Response
import net.dv8tion.jda.core.requests.RestAction
import net.dv8tion.jda.core.requests.Route
import net.dv8tion.jda.core.utils.JDALogger
import net.dv8tion.jda.core.utils.SessionController
import net.dv8tion.jda.core.utils.SessionControllerAdapter
import net.dv8tion.jda.core.utils.tuple.Pair
import org.json.JSONObject
import org.slf4j.Logger

import javax.security.auth.login.LoginException
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

open class DelayedSessionController(private val customDelay: Long) : SessionController {
    private val lock = Any()
    private val connectQueue: Queue<SessionController.SessionConnectNode>
    private val globalRatelimit: AtomicLong
    private var workerHandle: Thread? = null
    private var lastConnect: Long = 0

    init {
        connectQueue = ConcurrentLinkedQueue<SessionController.SessionConnectNode>()
        globalRatelimit = AtomicLong(java.lang.Long.MIN_VALUE)
    }

    override fun appendSession(node: SessionController.SessionConnectNode?) {
        connectQueue.add(node)
        runWorker()
    }

    override fun removeSession(node: SessionController.SessionConnectNode) {
        connectQueue.remove(node)
    }

    override fun getGlobalRatelimit(): Long {
        return globalRatelimit.get()
    }

    override fun setGlobalRatelimit(ratelimit: Long) {
        globalRatelimit.set(ratelimit)
    }

    override fun getGateway(api: JDA): String {
        val route = Route.Misc.GATEWAY.compile()
        return object : RestAction<String>(api, route) {
            override fun handleResponse(response: Response, request: Request<String>) {
                if (response.isOk)
                    request.onSuccess(response.getObject().getString("url"))
                else
                    request.onFailure(response)
            }
        }.complete()
    }

    override fun getGatewayBot(api: JDA): Pair<String, Int> {
        AccountTypeException.check(api.accountType, AccountType.BOT)
        return object : RestAction<Pair<String, Int>>(api, Route.Misc.GATEWAY_BOT.compile()) {
            override fun handleResponse(response: Response, request: Request<Pair<String, Int>>) {
                try {
                    when {
                        response.isOk -> {
                            val `object` = response.getObject()

                            val url = `object`.getString("url")
                            val shards = `object`.getInt("shards")

                            request.onSuccess(Pair.of(url, shards))
                        }
                        response.code == 401 -> (api as JDAImpl).verifyToken(true)
                        else -> request.onFailure(LoginException("When verifying the authenticity of the provided token, Discord returned an unknown response:\n" + response.toString()))
                    }
                } catch (e: Exception) {
                    request.onFailure(e)
                }

            }
        }.complete()
    }

    private fun runWorker() {
        synchronized(lock) {
            if (workerHandle == null) {
                workerHandle = QueueWorker(customDelay)
                workerHandle!!.start()
            }
        }
    }

    protected inner class QueueWorker
    /**
     * Creates a QueueWorker
     *
     * @param delay
     * delay (in milliseconds) to wait between starting sessions
     */
    internal constructor(
            /** Delay (in milliseconds) to sleep between connecting sessions  */
            private val delay: Long) : Thread("SessionControllerAdapter-Worker") {

        init {
            super.setUncaughtExceptionHandler { thread, exception -> this.handleFailure(thread, exception) }
        }

        private fun handleFailure(thread: Thread, exception: Throwable) {
            log.error("Worker has failed with throwable!", exception)
        }

        override fun run() {
            try {
                if (this.delay > 0) {
                    val interval = System.currentTimeMillis() - lastConnect
                    if (interval < this.delay)
                        Thread.sleep(this.delay - interval)
                }
            } catch (ex: InterruptedException) {
                log.error("Unable to backoff", ex)
            }

            processQueue()
            synchronized(lock) {
                workerHandle = null
                if (!connectQueue.isEmpty())
                    runWorker()
            }
        }

        private fun processQueue() {
            var isMultiple = connectQueue.size > 1
            while (!connectQueue.isEmpty()) {
                val node = connectQueue.poll()
                try {
                    node.run(isMultiple && connectQueue.isEmpty())
                    isMultiple = true
                    lastConnect = System.currentTimeMillis()
                    if (connectQueue.isEmpty())
                        break
                    if (this.delay > 0)
                        Thread.sleep(this.delay)
                } catch (e: IllegalStateException) {
                    val t = e.cause
                    if (t is OpeningHandshakeException)
                        log.error("Failed opening handshake, appending to queue. Message: {}", e.message)
                    else
                        log.error("Failed to establish connection for a node, appending to queue", e)
                    appendSession(node)
                } catch (e: InterruptedException) {
                    log.error("Failed to run node", e)
                    appendSession(node)
                    return  // caller should start a new thread
                }

            }
        }
    }

    companion object {
        private val log = JDALogger.getLog(DelayedSessionController::class.java)
    }

}
