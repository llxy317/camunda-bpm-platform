/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.integrationtest.functional.telemetry;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.camunda.bpm.engine.impl.telemetry.TelemetryRegistry.EXECUTED_DECISION_INSTANCES;
import static org.camunda.bpm.engine.impl.telemetry.TelemetryRegistry.FLOW_NODE_INSTANCES;
import static org.camunda.bpm.engine.impl.telemetry.TelemetryRegistry.ROOT_PROCESS_INSTANCES;
import static org.camunda.bpm.engine.impl.telemetry.TelemetryRegistry.UNIQUE_TASK_WORKERS;

import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;

import org.camunda.bpm.BpmPlatform;
import org.camunda.bpm.ProcessEngineService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.telemetry.dto.ApplicationServer;
import org.camunda.bpm.engine.impl.telemetry.dto.Command;
import org.camunda.bpm.engine.impl.telemetry.dto.Data;
import org.camunda.bpm.engine.impl.telemetry.dto.Database;
import org.camunda.bpm.engine.impl.telemetry.dto.Internals;
import org.camunda.bpm.engine.impl.telemetry.dto.Metric;
import org.camunda.bpm.engine.impl.telemetry.dto.Product;
import org.camunda.bpm.engine.impl.telemetry.reporter.TelemetryReporter;
import org.camunda.bpm.integrationtest.util.AbstractFoxPlatformIntegrationTest;
import org.camunda.bpm.integrationtest.util.TestContainer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.google.gson.Gson;

/**
 * @author Thorben Lindhauer
 *
 */
@RunWith(Arquillian.class)
public class TelemetryConnectPluginTest extends AbstractFoxPlatformIntegrationTest {

  ProcessEngine engine1;
  ProcessEngineConfigurationImpl configuration;


  @Before
  public void setEngines() {
    ProcessEngineService engineService = BpmPlatform.getProcessEngineService();
    engine1 = engineService.getProcessEngine("engine1");
    configuration = (ProcessEngineConfigurationImpl) engine1.getProcessEngineConfiguration();
  }

  @After
  public void tearDown() {
    configuration.getManagementService().toggleTelemetry(true);
  }

  @Deployment(name="myDeployment")
  public static WebArchive createDeployment() {
    final WebArchive webArchive =
        initWebArchiveDeployment("paConnectPlugin.war", "org/camunda/bpm/integrationtest/telemetry/processes-connectPlugin.xml");

    TestContainer.addContainerSpecificProcessEngineConfigurationClass(webArchive);
    return webArchive;
  }

  @Test
  @OperateOnDeployment("myDeployment")
  public void testHttpConnectorInitialized() {
    // when

    // then
    assertThat(configuration.getTelemetryHttpConnector()).isNotNull();
  }

  @Test
  @OperateOnDeployment("myDeployment")
  public void testGracefulDegradationOnMissingBean() {
    // given
    configuration.getManagementService().toggleTelemetry(true);
    Data data = createDataToSend();
    String requestBody = new Gson().toJson(data);
    stubFor(post(urlEqualTo("/pings"))
            .willReturn(aResponse()
                        .withBody(requestBody)
                        .withStatus(HttpURLConnection.HTTP_ACCEPTED)));

    TelemetryReporter telemetryReporter = new TelemetryReporter(configuration.getCommandExecutorTxRequired(),
                                                                configuration.getTelemetryEndpoint(),
                                                                data,
                                                                configuration.getTelemetryHttpConnector());

    // when
    telemetryReporter.reportNow();

    // then
    verify(postRequestedFor(urlEqualTo("/pings"))
              .withRequestBody(equalToJson(requestBody))
              .withHeader("Content-Type",  equalTo("application/json")));
  }

  protected Data createDataToSend() {
    Database database = new Database("mySpecialDb", "v.1.2.3");
    Internals internals = new Internals(database, new ApplicationServer("Apache Tomcat/10.0.1"));

    Map<String, Command> commands = getDefaultCommandCounts();
    internals.setCommands(commands);

    Map<String, Metric> metrics = getDefaultMetrics();
    internals.setMetrics(metrics);

    Product product = new Product("Runtime", "7.14.0", "special", internals);
    Data data = new Data("f5b19e2e-b49a-11ea-b3de-0242ac130004", product);
    return data;
  }

  protected Map<String, Command> getDefaultCommandCounts() {
    Map<String, Command> commands = new HashMap<>();
    commands.put("TelemetryConfigureCmd", new Command(1));
    commands.put("IsTelemetryEnabledCmd", new Command(1));
    return commands;
  }

  protected Map<String, Metric> getDefaultMetrics() {
    return assembleMetrics(0, 0, 0, 0);
  }
  protected Map<String, Metric> assembleMetrics(long processCount, long decisionCount, long flowNodeCount, long workerCount) {
    Map<String, Metric> metrics = new HashMap<>();
    metrics.put(ROOT_PROCESS_INSTANCES, new Metric(processCount));
    metrics.put(EXECUTED_DECISION_INSTANCES, new Metric(decisionCount));
    metrics.put(FLOW_NODE_INSTANCES, new Metric(flowNodeCount));
    metrics.put(UNIQUE_TASK_WORKERS, new Metric(workerCount));
    return metrics;
  }

}
