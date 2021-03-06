/*
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
package com.addthis.hydra.job.web;

import java.io.File;
import java.io.IOException;

import com.addthis.basis.util.Parameter;

import com.addthis.codec.jackson.Jackson;
import com.addthis.hydra.job.spawn.Spawn;
import com.addthis.hydra.job.web.jersey.KVPairsProvider;
import com.addthis.hydra.job.web.jersey.OptionalQueryParamInjectableProvider;
import com.addthis.hydra.job.web.resources.AlertResource;
import com.addthis.hydra.job.web.resources.AliasResource;
import com.addthis.hydra.job.web.resources.AuthenticationResource;
import com.addthis.hydra.job.web.resources.CommandResource;
import com.addthis.hydra.job.web.resources.HostResource;
import com.addthis.hydra.job.web.resources.JobsResource;
import com.addthis.hydra.job.web.resources.ListenResource;
import com.addthis.hydra.job.web.resources.MacroResource;
import com.addthis.hydra.job.web.resources.SpawnConfig;
import com.addthis.hydra.job.web.resources.SystemResource;
import com.addthis.hydra.job.web.resources.TaskResource;
import com.addthis.hydra.util.WebSocketManager;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.io.Files;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.spi.container.servlet.ServletContainer;
import com.yammer.metrics.reporting.MetricsServlet;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpawnService {

    private static final Logger log = LoggerFactory.getLogger(SpawnService.class);
    private static final int batchInterval = Integer.parseInt(System.getProperty("spawn.batchtime", "500"));
    private static final int pollTimeout = Integer.parseInt(System.getProperty("spawn.polltime", "1000"));

    private static final String webDir = Parameter.value("spawn.web.dir", "web");
    private static final String indexFilename = Parameter.value("spawn.index.file", "index.html");

    private final SpawnServiceConfiguration configuration;

    private final boolean sslEnabled;
    private final Server jetty;
    private final SpawnConfig servlets;
    private final WebSocketManager webSocketManager;

    private static String readFile(String path) throws IOException {
        return Files.toString(new File(path), Charsets.UTF_8).trim();
    }

    public SpawnService(final Spawn spawn, SpawnServiceConfiguration configuration) throws Exception {
        this.jetty = new Server();
        this.configuration = configuration;

        SelectChannelConnector selectChannelConnector = new SelectChannelConnector();
        selectChannelConnector.setPort(configuration.webPort);
        String keyStorePath = configuration.keyStorePath;
        String keyStorePassword = configuration.keyStorePassword;
        String keyManagerPassword = configuration.keyManagerPassword;

        if (!Strings.isNullOrEmpty(keyStorePassword) &&
            !Strings.isNullOrEmpty(keyManagerPassword) &&
            !Strings.isNullOrEmpty(keyStorePath)) {
            SslSelectChannelConnector sslSelectChannelConnector = new SslSelectChannelConnector();
            sslSelectChannelConnector.setPort(configuration.webPortSSL);
            SslContextFactory sslContextFactory = sslSelectChannelConnector.getSslContextFactory();
            sslContextFactory.setKeyStorePath(keyStorePath);
            sslContextFactory.setKeyStorePassword(readFile(keyStorePassword));
            sslContextFactory.setKeyManagerPassword(readFile(keyManagerPassword));
            log.info("Registering ssl connector");
            jetty.setConnectors(new Connector[]{selectChannelConnector, sslSelectChannelConnector});
            sslEnabled = true;
        } else if (configuration.requireSSL) {
            String message = "Missing one or more of \"com.addthis.hydra.job.spawn.Spawn.keyStorePath\", " +
                             "\"com.addthis.hydra.job.spawn.Spawn.keyStorePassword\", " +
                             "and \"com.addthis.hydra.job.spawn.Spawn.keyManagerPassword\". " +
                             "Set \"com.addthis.hydra.job.spawn.Spawn.requireSSL\" to false to disable SSL.";
            throw new IllegalStateException(message);
        } else {
            log.info("Not registering ssl connector");
            jetty.setConnectors(new Connector[]{selectChannelConnector});
            sslEnabled = false;
        }
        spawn.getSystemManager().updateSslEnabled(sslEnabled);

        this.servlets = new SpawnConfig();
        this.webSocketManager = spawn.getWebSocketManager();

        //instantiate resources
        SystemResource systemResource = new SystemResource(spawn);
        ListenResource listenResource = new ListenResource(spawn, systemResource, pollTimeout);
        JobsResource jobsResource = new JobsResource(spawn, new JobRequestHandlerImpl(spawn));
        MacroResource macroResource = new MacroResource(spawn.getJobMacroManager());
        CommandResource commandResource = new CommandResource(spawn.getJobCommandManager());
        TaskResource taskResource = new TaskResource(spawn);
        AliasResource aliasResource = new AliasResource(spawn);
        HostResource hostResource = new HostResource(spawn);
        AlertResource alertResource = new AlertResource(spawn.getJobAlertManager());
        AuthenticationResource authenticationResource = new AuthenticationResource(spawn);

        //register resources
        servlets.addResource(systemResource);
        servlets.addResource(listenResource);
        servlets.addResource(jobsResource);
        servlets.addResource(macroResource);
        servlets.addResource(commandResource);
        servlets.addResource(taskResource);
        servlets.addResource(aliasResource);
        servlets.addResource(hostResource);
        servlets.addResource(alertResource);
        servlets.addResource(authenticationResource);

        //register providers
        servlets.addProvider(OptionalQueryParamInjectableProvider.class);
        servlets.addProvider(KVPairsProvider.class);
        servlets.addProvider(new JacksonJsonProvider(Jackson.defaultMapper()));

        //Feature settings
        servlets.getFeatures().put(JSONConfiguration.FEATURE_POJO_MAPPING, Boolean.TRUE);
    }

    public void start() throws Exception {
        log.info("[init] running spawn2 on port " + configuration.webPort +
                 (sslEnabled ? (" and port " + configuration.webPortSSL) : ""));

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setDirectoriesListed(true);
        resourceHandler.setWelcomeFiles(new String[]{indexFilename});
        resourceHandler.setResourceBase(webDir);

        ServletContextHandler handler = new ServletContextHandler();
        webSocketManager.setHandler(handler);

        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[]{resourceHandler, webSocketManager});

        ServletContainer servletContainer = new ServletContainer(servlets);
        ServletHolder sh = new ServletHolder(servletContainer);

        handler.addServlet(new ServletHolder(new MetricsServlet()), "/metrics");
        handler.addServlet(sh, "/*");


        //jetty stuff
        jetty.setAttribute("org.eclipse.jetty.Request.maxFormContentSize", 5000000);
        //jetty.setHandler(webSocketManager);
        jetty.setHandler(handlers);
        jetty.start();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                try {
                    jetty.stop();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
