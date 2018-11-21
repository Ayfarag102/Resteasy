package org.jboss.resteasy.plugins.server.vertx;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.JAXRS;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SniHandler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetServerOptions;

import org.jboss.resteasy.spi.ResteasyDeployment;
import org.jboss.resteasy.plugins.server.embedded.EmbeddedJaxrsServer;
import org.jboss.resteasy.plugins.server.embedded.SecurityDomain;

/**
 * An HTTP server that sends back the content of the received HTTP request in a
 * pretty plaintext form.
 *
 * @author Andy Taylor (andy.taylor@jboss.org)
 * @author <a href="http://gleamynode.net/">Trustin Lee</a>
 * @author Norman Maurer
 * @author Julien Viet
 * @version $Rev: 2080 $, $Date: 2010-01-26 18:04:19 +0900 (Tue, 26 Jan 2010) $
 */
public class VertxJaxrsServer implements EmbeddedJaxrsServer
{
   private static final ConcurrentMap<String, Helper> deploymentMap = new ConcurrentHashMap<>();

   protected VertxOptions vertxOptions = new VertxOptions();

   protected Vertx vertx;

   protected HttpServerOptions serverOptions = new HttpServerOptions();

   protected VertxResteasyDeployment deployment = new VertxResteasyDeployment();

   protected String root = "";

   protected SecurityDomain domain;

   private String deploymentID;

   private SSLContext sslContext;

   private SSLParameters sslParameters;

   private JAXRS.Configuration configuration;

   // default no idle timeout.

   public String getHost()
   {
      return serverOptions.getHost();
   }

   public void setHost(String hostname)
   {
      serverOptions.setHost(hostname);
   }

   public int getPort()
   {
      return serverOptions.getPort();
   }

   public void setPort(int port)
   {
      if (port == JAXRS.Configuration.FREE_PORT)
      {
         serverOptions.setPort(this.scanPort());
      }
      if (port == JAXRS.Configuration.DEFAULT_PORT)
      {
         serverOptions.setPort(DEFAULT_PORT);
      }
      serverOptions.setPort(port);
   }

   public VertxOptions getVertxOptions()
   {
      return vertxOptions;
   }

   /**
    * Set {@link io.vertx.core.VertxOptions}.
    *
    * @param options
    *           the {@link io.vertx.core.VertxOptions}.
    * @see Vertx#vertx(VertxOptions)
    */
   public void setVertxOptions(VertxOptions options)
   {
      this.vertxOptions = options;
   }

   /**
    * Set {@link io.vertx.core.http.HttpServerOptions}.
    *
    * @param options
    *           the {@link io.vertx.core.http.HttpServerOptions}.
    * @see Vertx#createHttpServer(HttpServerOptions)
    */
   public void setServerOptions(HttpServerOptions options)
   {
      this.serverOptions = options;
   }

   public HttpServerOptions getServerOptions()
   {
      return serverOptions;
   }

   @Override
   public void setDeployment(ResteasyDeployment resteasyDeployment)
   {
      this.deployment.setApplication(resteasyDeployment.getApplication());
   }

   @Override
   public void setRootResourcePath(String rootResourcePath)
   {
      root = rootResourcePath;
      if (root != null && root.equals("/"))
         root = "";
   }

   @Override
   public VertxResteasyDeployment getDeployment()
   {
      return deployment;
   }

   @Override
   public void setSecurityDomain(SecurityDomain sc)
   {
      this.domain = sc;
   }

   @SuppressWarnings("unchecked")
   @Override
   public void start()
   {
      vertx = Vertx.vertx(vertxOptions);

      deployment.start();
      // if server port is not defined then use EmbeddedServer default port 8080
      // Vertx takes the Random free port as the default port;
      if (serverOptions.getPort() == NetServerOptions.DEFAULT_PORT)
      {
         serverOptions.setPort(DEFAULT_PORT);
      }
      if (deployment.getApplication() != null)
      {
         ApplicationPath appPath = deployment.getApplication().getClass().getAnnotation(ApplicationPath.class);
         if (appPath != null && (root == null || "".equals(root)))
         {
            // annotation is present and original root is not set
            String path = appPath.value();
            setRootResourcePath(path);
         }
         if (appPath != null && (root != null && !"".equals(root)))
         {
            root = root + appPath.value();
         }
      }
      String key = UUID.randomUUID().toString();

      if (configuration != null
            && configuration.property(HttpServerOptions.class.getName()) instanceof HttpServerOptions)
      {
         Object httpServerConfig = configuration.property(HttpServerOptions.class.getName());
         HttpServerOptions httpServerOptions = new HttpServerOptions((HttpServerOptions) httpServerConfig);
         httpServerOptions.setHost(serverOptions.getHost());
         httpServerOptions.setPort(serverOptions.getPort());
         deploymentMap.put(key, new Helper(root, httpServerOptions, deployment, domain));
      }
      else
      {
         deploymentMap.put(key, new Helper(root, serverOptions, deployment, domain));
      }
      // Configure the server.
      CompletableFuture<String> fut = new CompletableFuture<>();
      DeploymentOptions deploymentOptions = new DeploymentOptions().setInstances(vertxOptions.getEventLoopPoolSize())
            .setConfig(new JsonObject().put("helper", key));
      vertx.deployVerticle(Verticle.class.getName(), deploymentOptions, ar -> {
         deploymentMap.remove(key);
         if (ar.succeeded())
         {
            fut.complete(ar.result());
         }
         else
         {
            fut.completeExceptionally(ar.cause());
         }
      });
      try
      {
         deploymentID = fut.get(60, TimeUnit.SECONDS);
      }
      catch (InterruptedException e)
      {
         Thread.currentThread().interrupt();
      }
      catch (ExecutionException e)
      {
         throw new RuntimeException(e.getCause());
      }
      catch (TimeoutException e)
      {
         throw new RuntimeException(e);
      }
   }

   @Override
   public void setConfiguration(JAXRS.Configuration configuration)
   {
      this.configuration = configuration;
   }

   @Override
   public void stop()
   {
      if (deploymentID != null)
      {
         CompletableFuture<Void> fut = new CompletableFuture<>();
         vertx.close(ar -> {
            fut.complete(null);
         });
         deploymentID = null;
         try
         {
            fut.get(10, TimeUnit.SECONDS);
         }
         catch (InterruptedException e)
         {
            Thread.currentThread().interrupt();
         }
         catch (Exception ignore)
         {
         }
      }
   }

   private static class Helper
   {
      final String root;

      final HttpServerOptions serverOptions;

      final ResteasyDeployment deployment;

      final SecurityDomain domain;

      Helper(final String root, final HttpServerOptions serverOptions, final ResteasyDeployment deployment, final SecurityDomain domain)
      {
         this.root = root;
         this.serverOptions = serverOptions;
         this.deployment = deployment;
         this.domain = domain;
      }

      public Handler<HttpServerRequest> createHandler(Vertx vertx)
      {
         return new VertxRequestHandler(vertx, deployment, root, domain);
      }
   }

   public static class Verticle extends AbstractVerticle
   {

      protected HttpServer server;

      @Override
      public void start(Future<Void> startFuture) throws Exception
      {
         Helper helper = deploymentMap.get(config().getString("helper"));
         server = vertx.createHttpServer(helper.serverOptions);
         server.requestHandler(new VertxRequestHandler(vertx, helper.deployment, helper.root, helper.domain));
         server.listen(ar -> {
            if (ar.succeeded())
            {
               startFuture.complete();
            }
            else
            {
               startFuture.fail(ar.cause());
            }
         });
      }
   }

   @Override
   public void setSSLContext(SSLContext sslContext)
   {
      this.sslContext = sslContext;
   }

   @Override
   public void setProtocol(String protocol)
   {
      // TODO Auto-generated method stub

   }

   @Override
   public void setSslParameters(SSLParameters sslParameters)
   {
      this.sslParameters = sslParameters;

   }
}
