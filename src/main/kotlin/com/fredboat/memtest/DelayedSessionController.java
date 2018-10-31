package com.fredboat.memtest;

import com.neovisionaries.ws.client.OpeningHandshakeException;
import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.exceptions.AccountTypeException;
import net.dv8tion.jda.core.requests.Request;
import net.dv8tion.jda.core.requests.Response;
import net.dv8tion.jda.core.requests.RestAction;
import net.dv8tion.jda.core.requests.Route;
import net.dv8tion.jda.core.utils.JDALogger;
import net.dv8tion.jda.core.utils.SessionController;
import net.dv8tion.jda.core.utils.SessionControllerAdapter;
import net.dv8tion.jda.core.utils.tuple.Pair;
import org.json.JSONObject;
import org.slf4j.Logger;

import javax.security.auth.login.LoginException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class DelayedSessionController implements SessionController {

    private static final Logger log = JDALogger.getLog(SessionControllerAdapter.class);
    private final Object lock = new Object();
    private Queue<SessionConnectNode> connectQueue;
    private AtomicLong globalRatelimit;
    private Thread workerHandle;
    private long lastConnect = 0;
    private long customDelay;

    public DelayedSessionController(long customDelay)
    {
        connectQueue = new ConcurrentLinkedQueue<>();
        globalRatelimit = new AtomicLong(Long.MIN_VALUE);
        this.customDelay = customDelay;
    }

    @Override
    public void appendSession(SessionConnectNode node)
    {
        connectQueue.add(node);
        runWorker();
    }

    @Override
    public void removeSession(SessionConnectNode node)
    {
        connectQueue.remove(node);
    }

    @Override
    public long getGlobalRatelimit()
    {
        return globalRatelimit.get();
    }

    @Override
    public void setGlobalRatelimit(long ratelimit)
    {
        globalRatelimit.set(ratelimit);
    }

    @Override
    public String getGateway(JDA api)
    {
        Route.CompiledRoute route = Route.Misc.GATEWAY.compile();
        return new RestAction<String>(api, route)
        {
            @Override
            protected void handleResponse(Response response, Request<String> request)
            {
                if (response.isOk())
                    request.onSuccess(response.getObject().getString("url"));
                else
                    request.onFailure(response);
            }
        }.complete();
    }

    @Override
    public Pair<String, Integer> getGatewayBot(JDA api)
    {
        AccountTypeException.check(api.getAccountType(), AccountType.BOT);
        return new RestAction<Pair<String, Integer>>(api, Route.Misc.GATEWAY_BOT.compile())
        {
            @Override
            protected void handleResponse(Response response, Request<Pair<String, Integer>> request)
            {
                try
                {
                    if (response.isOk())
                    {
                        JSONObject object = response.getObject();

                        String url = object.getString("url");
                        int shards = object.getInt("shards");

                        request.onSuccess(Pair.of(url, shards));
                    }
                    else if (response.code == 401)
                    {
                        api.get().verifyToken(true);
                    }
                    else
                    {
                        request.onFailure(new LoginException("When verifying the authenticity of the provided token, Discord returned an unknown response:\n" +
                                response.toString()));
                    }
                }
                catch (Exception e)
                {
                    request.onFailure(e);
                }
            }
        }.complete();
    }

    private void runWorker()
    {
        synchronized (lock)
        {
            if (workerHandle == null)
            {
                workerHandle = new DelayedSessionController.QueueWorker(customDelay);
                workerHandle.start();
            }
        }
    }

    protected class QueueWorker extends Thread
    {
        /** Delay (in milliseconds) to sleep between connecting sessions */
        final long delay;

        QueueWorker()
        {
            this(IDENTIFY_DELAY);
        }

        /**
         * Creates a QueueWorker
         *
         * @param delay
         *        delay (in seconds) to wait between starting sessions
         */
        QueueWorker(int delay)
        {
            this(TimeUnit.SECONDS.toMillis(delay));
        }

        /**
         * Creates a QueueWorker
         *
         * @param delay
         *        delay (in milliseconds) to wait between starting sessions
         */
        QueueWorker(long delay)
        {
            super("SessionControllerAdapter-Worker");
            this.delay = delay;
            super.setUncaughtExceptionHandler(this::handleFailure);
        }

        void handleFailure(Thread thread, Throwable exception)
        {
            log.error("Worker has failed with throwable!", exception);
        }

        @Override
        public void run()
        {
            try
            {
                if (this.delay > 0)
                {
                    final long interval = System.currentTimeMillis() - lastConnect;
                    if (interval < this.delay)
                        Thread.sleep(this.delay - interval);
                }
            }
            catch (InterruptedException ex)
            {
                log.error("Unable to backoff", ex);
            }
            processQueue();
            synchronized (lock)
            {
                workerHandle = null;
                if (!connectQueue.isEmpty())
                    runWorker();
            }
        }

        void processQueue()
        {
            boolean isMultiple = connectQueue.size() > 1;
            while (!connectQueue.isEmpty())
            {
                SessionConnectNode node = connectQueue.poll();
                try
                {
                    node.run(isMultiple && connectQueue.isEmpty());
                    isMultiple = true;
                    lastConnect = System.currentTimeMillis();
                    if (connectQueue.isEmpty())
                        break;
                    if (this.delay > 0)
                        Thread.sleep(this.delay);
                }
                catch (IllegalStateException e)
                {
                    Throwable t = e.getCause();
                    if (t instanceof OpeningHandshakeException)
                        log.error("Failed opening handshake, appending to queue. Message: {}", e.getMessage());
                    else
                        log.error("Failed to establish connection for a node, appending to queue", e);
                    appendSession(node);
                }
                catch (InterruptedException e)
                {
                    log.error("Failed to run node", e);
                    appendSession(node);
                    return; // caller should start a new thread
                }
            }
        }
    }

}
