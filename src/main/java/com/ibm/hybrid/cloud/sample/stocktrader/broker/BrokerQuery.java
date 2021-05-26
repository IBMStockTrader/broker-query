/*
       Copyright 2021 IBM Corp All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.ibm.hybrid.cloud.sample.stocktrader.broker;

import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Account;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Broker;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Portfolio;


import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;

//Logging (JSR 47)
import java.util.logging.Level;
import java.util.logging.Logger;

//JCache
import javax.cache.Cache;
import javax.cache.Caching;
import javax.cache.CacheManager;
import javax.cache.configuration.MutableConfiguration;

//CDI 2.0
import javax.inject.Inject;
import javax.enterprise.context.RequestScoped;

//mpConfig 1.3
import org.eclipse.microprofile.config.inject.ConfigProperty;

//mpJWT 1.1
import org.eclipse.microprofile.auth.LoginConfig;

//mpOpenTracing
import org.eclipse.microprofile.opentracing.Traced;

//Servlet 4.0
import javax.servlet.http.HttpServletRequest;

//JAX-RS 2.1 (JSR 339)
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.Path;

@ApplicationPath("/")
@Path("/")
@LoginConfig(authMethod = "MP-JWT", realmName = "jwt-jaspi")
@RequestScoped //enable interceptors like @Transactional (note you need a WEB-INF/beans.xml in your war)
/** This microservice maintains a cache of the Portfolio (and optionally Account) objects, using JCache.
 *  You can provide whatever JCache provider you prefer.  For now, I'm using Redis (via the Redisson Java API);
 *  in the future, I'll probably switch over to using Red Hat Data Grid - but shouldn't need to touch any of
 *  this code to do so.  Note this microservice receives the Kafka messages (via MicroProfile Reactive Messaging)
 *  sent from Portfolio (and optionally Account).  This is the Q part of the CQRS architecture.  The "real"
 *  Broker microservice does a passthrough to this one for GET calls, if the env var is set to use CQRS mode.
 */
public class BrokerQuery extends Application {
	private static Logger logger = Logger.getLogger(BrokerQuery.class.getName());

	private static Cache<String, Broker> cache = null;


	@Traced
	private void initialize() { //let exceptions flow back to caller
		if (cache == null) {
			logger.fine("Entering initialize");

			CacheManager manager = Caching.getCachingProvider().getCacheManager();
			cache = manager.getCache("broker", String.class, Broker.class);
			if (cache == null) {
				logger.info("No cache found named broker");
				MutableConfiguration<String, Broker> config = new MutableConfiguration<>();
//				config.setTypes(String.class, Broker.class);
				cache = manager.createCache("broker", config);
			}

			logger.fine("Exiting initialize");
		}
	}

	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
//	@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
	public Broker[] getBrokers(@Context HttpServletRequest request) {
		Broker[] brokers = null;
		int count = 0;
		try {
			logger.fine("Entering getBrokers");
			if (cache == null) initialize();

			ArrayList<Broker> list = new ArrayList<Broker>();
			Iterator<Cache.Entry<String, Broker>> iter = cache.iterator();
			while (iter.hasNext()) {
				Cache.Entry<String, Broker> entry = iter.next();
				Broker broker = entry.getValue();
				logger.finer("Adding "+broker.getOwner()+" to list in getBrokers");
				list.add(broker);
				count++;
			}

			brokers = list.toArray(brokers);
		} catch (Throwable t) {
			logException(t);
			if (t instanceof RuntimeException) {
				RuntimeException re = (RuntimeException) t;
				logger.warning("Throwing "+t.getClass().getName()+" in getBrokers");
				throw re;
			}
		}

		logger.fine("Returning "+count+" brokers");

		return brokers;
	}

	@POST
	@Path("/{owner}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	//	@RolesAllowed({"StockTrader"}) //Couldn't get this to work; had to do it through the web.xml instead :(
	/* This is just a test method - it wouldn't get called in real usage.  It's just a handy way to load something
	 * into the cache if the Kafka stuff isn't configured, so that the "GET /" and "GET /broker" calls can be tested
	 */
	public Broker createBroker(@PathParam("owner") String owner, Broker broker, @Context HttpServletRequest request) {
		try {
			logger.fine("Entering createBroker for "+owner);
			if (cache == null) initialize();

			logger.fine("Putting "+owner+" in cache");
			cache.put(owner, broker);
			logger.fine("Cache update successful for "+owner);
		} catch (Throwable t) {
			logException(t);
			if (t instanceof RuntimeException) {
				RuntimeException re = (RuntimeException) t;
				logger.warning("Throwing "+t.getClass().getName()+" in createBroker");
				throw re;
			}
		}

		return broker;
	}

	@GET
	@Path("/{owner}")
	@Produces(MediaType.APPLICATION_JSON)
//	@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
	public Broker getBroker(@PathParam("owner") String owner, @Context HttpServletRequest request) {
		Broker broker = null;

		try {
			logger.fine("Entering getBroker for "+owner);
			if (cache == null) initialize();

			logger.fine("Getting "+owner+" from cache");
			broker = cache.get(owner);
			logger.fine("Cache retrieval successful for "+owner);
			logger.finer(broker.toString());
		} catch (Throwable t) {
			logException(t);
			if (t instanceof RuntimeException) {
				RuntimeException re = (RuntimeException) t;
				logger.warning("Throwing "+t.getClass().getName()+" in getBroker");
				throw re;
			}
		}

		return broker;
	}
    
	static void logException(Throwable t) {
		logger.warning(t.getClass().getName()+": "+t.getMessage());

		//only log the stack trace if the level has been set to at least INFO
		if (logger.isLoggable(Level.INFO)) {
			StringWriter writer = new StringWriter();
			t.printStackTrace(new PrintWriter(writer));
			logger.info(writer.toString());
		}
	}
}
