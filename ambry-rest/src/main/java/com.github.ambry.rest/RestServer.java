/**
 * Copyright 2016 LinkedIn Corp. All rights reserved.
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
 */
package com.github.ambry.rest;

import com.codahale.metrics.*;
import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.commons.SSLFactory;
import com.github.ambry.config.RestServerConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.notification.NotificationSystem;
import com.github.ambry.router.Router;
import com.github.ambry.router.RouterFactory;
import com.github.ambry.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;


/**
 * The RestServer represents any RESTful service (frontend, admin etc.) whose main concern is to receive requests from
 * clients through a REST protocol (HTTP), handle them appropriately by contacting the backend service if required and
 * return responses via the same REST protocol.
 * <p/>
 * The RestServer is responsible for starting up (and shutting down) multiple services required to handle requests from
 * clients. Currently it starts/shuts down the following: -
 * 1. A {@link Router} - A service that is used to contact the backend service.
 * 2. A {@link BlobStorageService} - A service that understands the operations supported by the backend service and can
 * handle requests from clients for such operations.
 * 3. A {@link NioServer} - To receive requests and return responses via a REST protocol (HTTP).
 * 4. A {@link RestRequestHandler} and a {@link RestResponseHandler} - Scaling units that are responsible for
 * interfacing between the {@link NioServer} and the {@link BlobStorageService}.
 * 5. A {@link PublicAccessLogger} - To assist in public access logging
 * 6. A {@link RestServerState} - To maintain the health of the server
 * <p/>
 * Depending upon what is specified in the configuration file, the RestServer can start different implementations of
 * {@link NioServer} and {@link BlobStorageService} and behave accordingly.
 * <p/>
 * With RestServer, the goals are threefold:-
 * 1. To support ANY RESTful frontend service as long as it can provide an implementation of {@link BlobStorageService}.
 * 2. Make it easy to plug in any implementation of {@link NioServer} as long as it can provide implementations that
 * abstract framework specific objects and actions (like write/read from channel) into generic APIs through
 * {@link RestRequest}, {@link RestResponseChannel} etc.
 * 3. Provide scaling capabilities independent of any other component through {@link RestRequestHandler} and
 * {@link RestResponseHandler}.
 */
public class RestServer {
  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final RestServerMetrics restServerMetrics;
  private final List<Object> reporters;
  private final Router router;
  private final BlobStorageService blobStorageService;
  private final RestRequestHandler restRequestHandler;
  private final RestResponseHandler restResponseHandler;
  private final NioServer nioServer;
  private final PublicAccessLogger publicAccessLogger;
  private final RestServerState restServerState;

  /**
   * {@link RestServer} specific metrics tracking.
   */
  private class RestServerMetrics {
    // Errors
    public final Counter restServerInstantiationError;

    // Others
    public final Histogram blobStorageServiceShutdownTimeInMs;
    public final Histogram blobStorageServiceStartTimeInMs;
    public final Histogram nioServerShutdownTimeInMs;
    public final Histogram nioServerStartTimeInMs;
    public final Histogram jmxReporterShutdownTimeInMs;
    public final Histogram jmxReporterStartTimeInMs;
    public final Histogram restRequestHandlerShutdownTimeInMs;
    public final Histogram restRequestHandlerStartTimeInMs;
    public final Histogram restResponseHandlerShutdownTimeInMs;
    public final Histogram restResponseHandlerStartTimeInMs;
    public final Histogram restServerShutdownTimeInMs;
    public final Histogram restServerStartTimeInMs;
    public final Histogram routerCloseTime;

    /**
     * Creates an instance of RestServerMetrics using the given {@code metricRegistry}.
     * @param metricRegistry the {@link MetricRegistry} to use for the metrics.
     * @param restServerState the {@link RestServerState} object used to track the state of the {@link RestServer}.
     */
    public RestServerMetrics(MetricRegistry metricRegistry, final RestServerState restServerState) {
      // Errors
      restServerInstantiationError =
          metricRegistry.counter(MetricRegistry.name(RestServer.class, "InstantiationError"));

      // Others
      blobStorageServiceShutdownTimeInMs =
          metricRegistry.histogram(MetricRegistry.name(RestServer.class, "BlobStorageServiceShutdownTimeInMs"));
      blobStorageServiceStartTimeInMs =
          metricRegistry.histogram(MetricRegistry.name(RestServer.class, "BlobStorageServiceStartTimeInMs"));
      jmxReporterShutdownTimeInMs =
          metricRegistry.histogram(MetricRegistry.name(RestServer.class, "JmxShutdownTimeInMs"));
      jmxReporterStartTimeInMs = metricRegistry.histogram(MetricRegistry.name(RestServer.class, "JmxStartTimeInMs"));
      nioServerShutdownTimeInMs =
          metricRegistry.histogram(MetricRegistry.name(RestServer.class, "NioServerShutdownTimeInMs"));
      nioServerStartTimeInMs =
          metricRegistry.histogram(MetricRegistry.name(RestServer.class, "NioServerStartTimeInMs"));
      restRequestHandlerShutdownTimeInMs =
          metricRegistry.histogram(MetricRegistry.name(RestServer.class, "RestRequestHandlerShutdownTimeInMs"));
      restRequestHandlerStartTimeInMs =
          metricRegistry.histogram(MetricRegistry.name(RestServer.class, "RestRequestHandlerStartTimeInMs"));
      restResponseHandlerShutdownTimeInMs =
          metricRegistry.histogram(MetricRegistry.name(RestServer.class, "RestResponseHandlerShutdownTimeInMs"));
      restResponseHandlerStartTimeInMs =
          metricRegistry.histogram(MetricRegistry.name(RestServer.class, "RestResponseHandlerStartTimeInMs"));
      restServerShutdownTimeInMs =
          metricRegistry.histogram(MetricRegistry.name(RestServer.class, "RestServerShutdownTimeInMs"));
      restServerStartTimeInMs =
          metricRegistry.histogram(MetricRegistry.name(RestServer.class, "RestServerStartTimeInMs"));
      routerCloseTime = metricRegistry.histogram(MetricRegistry.name(RestServer.class, "RouterCloseTimeInMs"));

      Gauge<Integer> restServerStatus = new Gauge<Integer>() {
        @Override
        public Integer getValue() {
          return restServerState.isServiceUp() ? 1 : 0;
        }
      };
      metricRegistry.register(MetricRegistry.name(RestServer.class, "RestServerState"), restServerStatus);
    }
  }

  /**
   * Creates an instance of RestServer.
   * @param verifiableProperties the properties that define the behavior of the RestServer and its components.
   * @param clusterMap the {@link ClusterMap} instance that needs to be used.
   * @param notificationSystem the {@link NotificationSystem} instance that needs to be used.
   * @param sslFactory the {@link SSLFactory} to be used. This can be {@code null} if no components require SSL support.
   * @throws InstantiationException if there is any error instantiating an instance of RestServer.
   */
  public RestServer(VerifiableProperties verifiableProperties, ClusterMap clusterMap,
      NotificationSystem notificationSystem, SSLFactory sslFactory) throws Exception {
    if (verifiableProperties == null || clusterMap == null || notificationSystem == null) {
      throw new IllegalArgumentException("Null arg(s) received during instantiation of RestServer");
    }
    MetricRegistry metricRegistry = clusterMap.getMetricRegistry();
    RestServerConfig restServerConfig = new RestServerConfig(verifiableProperties);
    reporters = initReporters(metricRegistry, restServerConfig);
    RestRequestMetricsTracker.setDefaults(metricRegistry);
    restServerState = new RestServerState(restServerConfig.restServerHealthCheckUri);
    restServerMetrics = new RestServerMetrics(metricRegistry, restServerState);

    RouterFactory routerFactory =
        Utils.getObj(restServerConfig.restServerRouterFactory, verifiableProperties, clusterMap, notificationSystem,
            sslFactory);
    router = routerFactory.getRouter();

    RestResponseHandlerFactory restResponseHandlerFactory =
        Utils.getObj(restServerConfig.restServerResponseHandlerFactory,
            restServerConfig.restServerResponseHandlerScalingUnitCount, metricRegistry);
    restResponseHandler = restResponseHandlerFactory.getRestResponseHandler();

    BlobStorageServiceFactory blobStorageServiceFactory =
        Utils.getObj(restServerConfig.restServerBlobStorageServiceFactory, verifiableProperties, clusterMap,
            restResponseHandler, router);
    blobStorageService = blobStorageServiceFactory.getBlobStorageService();

    RestRequestHandlerFactory restRequestHandlerFactory = Utils.getObj(restServerConfig.restServerRequestHandlerFactory,
        restServerConfig.restServerRequestHandlerScalingUnitCount, metricRegistry, blobStorageService);
    restRequestHandler = restRequestHandlerFactory.getRestRequestHandler();
    publicAccessLogger = new PublicAccessLogger(restServerConfig.restServerPublicAccessLogRequestHeaders.split(","),
        restServerConfig.restServerPublicAccessLogResponseHeaders.split(","));

    NioServerFactory nioServerFactory =
        Utils.getObj(restServerConfig.restServerNioServerFactory, verifiableProperties, metricRegistry,
            restRequestHandler, publicAccessLogger, restServerState, sslFactory);
    nioServer = nioServerFactory.getNioServer();

    if (router == null || restResponseHandler == null || blobStorageService == null || restRequestHandler == null
        || nioServer == null) {
      throw new InstantiationException("Some of the server components were null");
    }
    logger.trace("Instantiated RestServer");
  }

  private List<Object> initReporters(MetricRegistry metricRegistry, RestServerConfig restServerConfig) {
    List<Object> reporters = new ArrayList<Object>();
    String[] reporterClassArray = restServerConfig.reporterClasses.split(",");
    for (String reporterClassName : reporterClassArray) {
      try {
        Class<?> reporterClass = Class.forName(reporterClassName);
        Method forRegistry = reporterClass.getDeclaredMethod("forRegistry");
        forRegistry.setAccessible(true);
        Object reporterBuilder = forRegistry.invoke(null, metricRegistry);
        Method reporterBuildMethod = reporterBuilder.getClass().getDeclaredMethod("build");
        reporterBuildMethod.setAccessible(true);
        Object reporter = reporterBuildMethod.invoke(reporterBuilder);
        reporters.add(reporter);
      } catch (Exception e) {
        logger.error("build reporter error, class not found", e);
        throw new RuntimeException(e);
      }
    }
    return reporters;
  }

  /**
   * Starts up all the components required. Returns when startup is FULLY complete.
   * @throws InstantiationException if the RestServer is unable to start.
   */
  public void start() throws InstantiationException {
    logger.info("Starting RestServer");
    long startupBeginTime = System.currentTimeMillis();
    try {
      // ordering is important.
      for (Object reporter : reporters) {
        startReporter(reporter);
      }
      long reporterStartTime = System.currentTimeMillis();
      long elapsedTime = reporterStartTime - startupBeginTime;
      logger.info("JMX reporter start took {} ms", elapsedTime);
      restServerMetrics.jmxReporterStartTimeInMs.update(elapsedTime);

      restResponseHandler.start();
      long restResponseHandlerStartTime = System.currentTimeMillis();
      elapsedTime = restResponseHandlerStartTime - reporterStartTime;
      logger.info("Response handler start took {} ms", elapsedTime);
      restServerMetrics.restResponseHandlerStartTimeInMs.update(elapsedTime);

      blobStorageService.start();
      long blobStorageServiceStartTime = System.currentTimeMillis();
      elapsedTime = blobStorageServiceStartTime - restResponseHandlerStartTime;
      logger.info("Blob storage service start took {} ms", elapsedTime);
      restServerMetrics.blobStorageServiceStartTimeInMs.update(elapsedTime);

      restRequestHandler.start();
      long restRequestHandlerStartTime = System.currentTimeMillis();
      elapsedTime = restRequestHandlerStartTime - blobStorageServiceStartTime;
      logger.info("Request handler start took {} ms", elapsedTime);
      restServerMetrics.restRequestHandlerStartTimeInMs.update(elapsedTime);

      nioServer.start();
      elapsedTime = System.currentTimeMillis() - restRequestHandlerStartTime;
      logger.info("NIO server start took {} ms", elapsedTime);
      restServerMetrics.nioServerStartTimeInMs.update(elapsedTime);

      restServerState.markServiceUp();
      logger.info("Service marked as up");
    } finally {
      long startupTime = System.currentTimeMillis() - startupBeginTime;
      logger.info("RestServer start took {} ms", startupTime);
      restServerMetrics.restServerStartTimeInMs.update(startupTime);
    }
  }

  private void startReporter(Object reporter) {
    invokeReporterMethod(reporter, "start");
  }

  /**
   * Shuts down all the components. Returns when shutdown is FULLY complete.
   */
  public void shutdown() {
    logger.info("Shutting down RestServer");
    long shutdownBeginTime = System.currentTimeMillis();
    try {
      //ordering is important.
      restServerState.markServiceDown();
      logger.info("Service marked as down ");
      nioServer.shutdown();
      long nioServerShutdownTime = System.currentTimeMillis();
      long elapsedTime = nioServerShutdownTime - shutdownBeginTime;
      logger.info("NIO server shutdown took {} ms", elapsedTime);
      restServerMetrics.nioServerShutdownTimeInMs.update(elapsedTime);

      restRequestHandler.shutdown();
      long requestHandlerShutdownTime = System.currentTimeMillis();
      elapsedTime = requestHandlerShutdownTime - nioServerShutdownTime;
      logger.info("Request handler shutdown took {} ms", elapsedTime);
      restServerMetrics.restRequestHandlerShutdownTimeInMs.update(elapsedTime);

      blobStorageService.shutdown();
      long blobStorageServiceShutdownTime = System.currentTimeMillis();
      elapsedTime = blobStorageServiceShutdownTime - requestHandlerShutdownTime;
      logger.info("Blob storage service shutdown took {} ms", elapsedTime);
      restServerMetrics.blobStorageServiceShutdownTimeInMs.update(elapsedTime);

      restResponseHandler.shutdown();
      long responseHandlerShutdownTime = System.currentTimeMillis();
      elapsedTime = responseHandlerShutdownTime - blobStorageServiceShutdownTime;
      logger.info("Response handler shutdown took {} ms", elapsedTime);
      restServerMetrics.restResponseHandlerShutdownTimeInMs.update(elapsedTime);

      router.close();
      long routerCloseTime = System.currentTimeMillis();
      elapsedTime = routerCloseTime - responseHandlerShutdownTime;
      logger.info("Router close took {} ms", elapsedTime);
      restServerMetrics.routerCloseTime.update(elapsedTime);

      for (Object reporter : reporters) {
        stopReporter(reporter);
      }
      elapsedTime = System.currentTimeMillis() - routerCloseTime;
      logger.info("JMX reporter shutdown took {} ms", elapsedTime);
      restServerMetrics.jmxReporterShutdownTimeInMs.update(elapsedTime);
    } catch (IOException e) {
      logger.error("Exception during shutdown", e);
    } finally {
      long shutdownTime = System.currentTimeMillis() - shutdownBeginTime;
      logger.info("RestServer shutdown took {} ms", shutdownTime);
      restServerMetrics.restServerShutdownTimeInMs.update(shutdownTime);
      shutdownLatch.countDown();
    }
  }

  private void stopReporter(Object reporter) {
    invokeReporterMethod(reporter, "stop");
  }

  private void invokeReporterMethod(Object reporter, String methodName) {
    Class<?> reporterClass = reporter.getClass();
    try {
      Method startMethod = reporterClass.getDeclaredMethod(methodName);
      startMethod.setAccessible(true);
      startMethod.invoke(reporter);
    } catch (Exception e) {
      logger.error("invoke reporter {}, method {} error", methodName, reporterClass, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Wait for shutdown to be triggered and for it to complete.
   * @throws InterruptedException if the wait for shutdown is interrupted.
   */
  public void awaitShutdown() throws InterruptedException {
    shutdownLatch.await();
  }
}
