/**
 * Copyright (C) 2011 Ovea <dev@ovea.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ovea.jetty.session.redis;

import com.ovea.jetty.session.SessionIdManagerSkeleton;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.TransactionBlock;
import redis.clients.jedis.exceptions.JedisException;

import javax.naming.InitialContext;
import java.util.LinkedList;
import java.util.List;

/**
 * @author Mathieu Carbou (mathieu.carbou@gmail.com)
 */
public final class RedisSessionIdManager extends SessionIdManagerSkeleton {

    final static Logger LOG = Log.getLogger("com.ovea.jetty.session");

    private static final Long ZERO = 0L;
    static final String REDIS_SESSIONS_KEY = "jetty-sessions-key-";
    static final String REDIS_SESSION_KEY = "jetty-session-value-";

    private final JedisExecutor jedisExecutor;

    public RedisSessionIdManager(Server server, JedisPool jedisPool) {
        super(server);
        this.jedisExecutor = new PooledJedisExecutor(jedisPool);
    }

    public RedisSessionIdManager(Server server, final String jndiName) {
        super(server);
        this.jedisExecutor = new JedisExecutor() {
            JedisExecutor delegate;

            @Override
            public <V> V execute(JedisCallback<V> cb) {
                if (delegate == null) {
                    try {
                        InitialContext ic = new InitialContext();
                        JedisPool jedisPool = (JedisPool) ic.lookup(jndiName);
                        delegate = new PooledJedisExecutor(jedisPool);
                    } catch (Exception e) {
                        throw new IllegalStateException("Unable to find instance of " + JedisExecutor.class.getName() + " in JNDI location " + jndiName + " : " + e.getMessage(), e);
                    }
                }
                return delegate.execute(cb);
            }
        };
    }

    @Override
    protected void deleteClusterId(final String clusterId) {
        jedisExecutor.execute(new JedisCallback<Object>() {
            @Override
            public Object execute(Jedis jedis) {
                return jedis.del(REDIS_SESSIONS_KEY + clusterId);
            }
        });
    }

    @Override
    protected void storeClusterId(final String clusterId) {
        jedisExecutor.execute(new JedisCallback<Object>() {
            @Override
            public Object execute(Jedis jedis) {
                return jedis.setex(REDIS_SESSIONS_KEY + clusterId, 3600, "1"); // RedisSessionManager.java method storeSession(final RedisSession session) will set correct expire time
            }
        });
    }

    @Override
    protected boolean hasClusterId(final String clusterId) {
        return jedisExecutor.execute(new JedisCallback<Boolean>() {
            @Override
            public Boolean execute(Jedis jedis) {
                return jedis.exists(REDIS_SESSIONS_KEY + clusterId);
            }
        });
    }

    @Override
    protected List<String> scavenge(final List<String> clusterIds) {
        List<String> expired = new LinkedList<String>();
        List<Object> status = jedisExecutor.execute(new JedisCallback<List<Object>>() {
            @Override
            public List<Object> execute(Jedis jedis) {
                return jedis.multi(new TransactionBlock() {
                    @Override
                    public void execute() throws JedisException {
                        for (String clusterId : clusterIds) {
                            exists(REDIS_SESSION_KEY + clusterId);
                        }
                    }
                });
            }
        });
        for (int i = 0; i < status.size(); i++)
            if (status.get(i) != null && status.get(i).equals(false))
                expired.add(clusterIds.get(i));
        if (LOG.isDebugEnabled() && !expired.isEmpty())
            LOG.debug("[RedisSessionIdManager] Scavenger found {} sessions to expire: {}", expired.size(), expired);
        return expired;
    }

}
