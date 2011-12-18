/*
 * Copyright (c) 2011 by the original author
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
package org.powertac.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.log4j.Logger;
import org.joda.time.DateTimeZone;
import org.joda.time.Instant;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.powertac.common.Competition;
import org.powertac.common.PluginConfig;
import org.powertac.common.TimeService;
import org.powertac.common.XMLMessageConverter;
import org.powertac.common.interfaces.BootstrapDataCollector;
import org.powertac.common.interfaces.CompetitionSetup;
import org.powertac.common.interfaces.InitializationService;
import org.powertac.common.repo.DomainRepo;
import org.powertac.common.repo.PluginConfigRepo;
import org.powertac.common.spring.SpringApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Manages command-line and file processing for pre-game simulation setup. 
 * A simulation can be started in one of two ways:
 * <ul>
 * <li>By running the process with command-line arguments as specified in
 * {@link PowerTacServer}, or</li>
 * <li> by calling the <code>preGame()</code> method to
 * set up the environment and allow configuration of the next game, through
 * a web (or REST) interface.</li>
 * </ul>
 * @author John Collins
 */
@Service
public class CompetitionSetupService
  implements CompetitionSetup
{
  static private Logger log = Logger.getLogger(CompetitionSetupService.class);

  @Autowired
  private CompetitionControlService cc;

  @Autowired
  private BootstrapDataCollector defaultBroker;

  @Autowired
  private PluginConfigRepo pluginConfigRepo;

  @Autowired
  private ServerPropertiesService serverProps;

  @Autowired
  private XMLMessageConverter messageConverter;

  @Autowired
  private LogService logService;

  private Competition competition;
  
  /**
   * Standard constructor
   */
  public CompetitionSetupService ()
  {
    super();
  }
  
  /**
   * Processes command-line arguments, which means looking for the specified 
   * script file and processing that
   */
  void processCmdLine (String[] args)
  {
    // pick up and process the command-line arg if it's there
    if (args.length == 1) {
      // running from config file
      try {
        BufferedReader config = new BufferedReader(new FileReader(args[0]));
        String input;
        while ((input = config.readLine()) != null) {
          String[] tokens = input.split("\\s+");
          if ("bootstrap".equals(tokens[0])) {
            // bootstrap mode - optional config fn is tokens[2]
            if (tokens.length == 2 || tokens.length > 3) {
              System.out.println("Bad input " + input);
            }
            else {
              if (tokens.length == 3 && "--config".equals(tokens[1])) {
                // explicit config file
                serverProps.setUserConfig(tokens[2]);
              }
              FileWriter bootWriter =
                  new FileWriter(serverProps.getProperty("server.bootstrapDataFile",
                                                         "bootstrapData.xml"));
              cc.setAuthorizedBrokerList(new ArrayList<String>());
              preGame();
              //cc.runOnce(bootWriter);
              cc.runOnce(true);
              saveBootstrapData(bootWriter);
            }
          }
          else if ("sim".equals(tokens[0])) {
            int brokerIndex = 1;
            // sim mode, check for --config in tokens[1]
            if (tokens.length < 2) {
              System.out.println("Bad input: " + input);
            }
            else if ("--config".equals(tokens[1])) {
              if (tokens.length < 4) {
                System.out.println("No brokers given for sim: " + input);
              }
              else {
                // explicit config file in tokens[2]
                serverProps.setUserConfig(tokens[2]);
              }
              brokerIndex = 3;
            }
            log.info("In Simulation mode!!!");
            File bootFile =
                new File(serverProps.getProperty("server.bootstrapDataFile",
                                                 "bd-noname.xml"));
            // collect broker names, hand to CC for login control
            ArrayList<String> brokerList = new ArrayList<String>();
            for (int i = brokerIndex; i < tokens.length; i++) {
              brokerList.add(tokens[i]);
            }
            if (brokerList.size() > 0) {
              cc.setAuthorizedBrokerList(brokerList);
              
              if (preGame(bootFile)) {
                cc.setBootstrapDataset(processBootDataset(bootFile));
                cc.runOnce(false);
              }
            }
            else {
              System.out.println("Cannot run sim without brokers");
            }
          }
        }
      }
      catch (FileNotFoundException fnf) {
        System.out.println("Cannot find file " + args[0]);
      }
      catch (IOException ioe ) {
        System.out.println("Error reading file " + args[0]);
      }
    }
    else if (args.length == 0) {
      // running from web interface
      System.out.println("Server BootStrap");
      //participantManagementService.initialize();
      preGame();

      // idle while the web interface controls the simulator
      while(true) {
        try {
          Thread.sleep(5000);
        } catch (InterruptedException e) {

        }
      }
    }
    else { // usage problem
      System.out.println("Usage: powertac-server [filename]");
    }
  }
  

  /**
   * Pre-game server setup - creates the basic configuration elements
   * to make them accessible to the web-based game-setup functions.
   * This method must be called when the server is started, and again at
   * the completion of each simulation. The actual simulation is started
   * with a call to init().
   */
  public void preGame ()
  {
    log.info("preGame() - start");
    // Create default competition
    competition = Competition.newInstance("defaultCompetition");
    //competitionId = competition.getId();
    String suffix = serverProps.getProperty("server.logfileSuffix", "x");
    logService.startLog(suffix);

    // Set up all the plugin configurations
    log.info("pre-game initialization");
    configureCompetition(competition);

    // Handle pre-game initializations by clearing out the repos,
    // then creating the PluginConfig instances
    List<DomainRepo> repos =
      SpringApplicationContext.listBeansOfType(DomainRepo.class);
    log.debug("found " + repos.size() + " repos");
    for (DomainRepo repo : repos) {
      repo.recycle();
    }
    List<InitializationService> initializers =
      SpringApplicationContext.listBeansOfType(InitializationService.class);
    log.debug("found " + initializers.size() + " initializers");
    for (InitializationService init : initializers) {
      init.setDefaults();
    }
  }
  
  // configures a Competition from server.properties
  private void configureCompetition (Competition competition2)
  {
    // get game length
    int minimumTimeslotCount =
        serverProps.getIntegerProperty("competition.minimumTimeslotCount",
                                       competition.getMinimumTimeslotCount());
    int expectedTimeslotCount =
        serverProps.getIntegerProperty("competition.expectedTimeslotCount",
                                       competition.getExpectedTimeslotCount());
    if (expectedTimeslotCount < minimumTimeslotCount) {
      log.warn("competition expectedTimeslotCount " + expectedTimeslotCount
               + " < minimumTimeslotCount " + minimumTimeslotCount);
      expectedTimeslotCount = minimumTimeslotCount;
    }
    int bootstrapTimeslotCount =
        serverProps.getIntegerProperty("competition.bootstrapTimeslotCount",
                                       competition.getBootstrapTimeslotCount());

    // get trading parameters
    int timeslotsOpen =
        serverProps.getIntegerProperty("competition.timeslotsOpen",
                                       competition.getTimeslotsOpen());
    int deactivateTimeslotsAhead =
        serverProps.getIntegerProperty("competition.deactivateTimeslotsAhead",
                                       competition.getDeactivateTimeslotsAhead());

    // get time parameters
    int timeslotLength =
        serverProps.getIntegerProperty("competition.timeslotLength",
                                       competition.getTimeslotLength());
    int simulationTimeslotSeconds =
        timeslotLength * 60 / (int)competition.getSimulationRate();
    simulationTimeslotSeconds =
        serverProps.getIntegerProperty("competition.simulationTimeslotSeconds",
                                       simulationTimeslotSeconds);
    int simulationRate = timeslotLength * 60 / simulationTimeslotSeconds;
    DateTimeZone.setDefault(DateTimeZone.UTC);
    DateTimeFormatter fmt = DateTimeFormat.forPattern("yyyy-MM-dd");
    Instant start = null;
    try {
      start =
        fmt.parseDateTime(serverProps.getProperty("competition.baseTime")).toInstant();
    }
    catch (Exception e) {
      log.error("Exception reading base time: " + e.toString());
    }
    if (start == null)
      start = competition.getSimulationBaseTime();

    // populate the competition instance
    competition
      .withMinimumTimeslotCount(minimumTimeslotCount)
      .withExpectedTimeslotCount(expectedTimeslotCount)
      .withSimulationBaseTime(start)
      .withSimulationRate(simulationRate)
      .withTimeslotLength(timeslotLength)
      .withSimulationModulo(timeslotLength * TimeService.MINUTE)
      .withTimeslotsOpen(timeslotsOpen)
      .withDeactivateTimeslotsAhead(deactivateTimeslotsAhead)
      .withBootstrapTimeslotCount(bootstrapTimeslotCount);
    
    // bootstrap timeslot timing is a local parameter
    int bootstrapTimeslotSeconds =
        serverProps.getIntegerProperty("competition.bootstrapTimeslotSeconds",
                                       (int)(cc.getBootstrapTimeslotMillis()
                                             / TimeService.SECOND));
    cc.setBootstrapTimeslotMillis(bootstrapTimeslotSeconds * TimeService.SECOND);
  }

  /**
   * Sets up the simulator, with config overrides provided in a file
   * containing a sequence of PluginConfig instances. Errors are logged
   * if one or more PluginConfig instances cannot be used in the current
   * server setup.
   */
  public boolean preGame (File bootFile)
  {
    log.info("preGame(File) - start");
    // run the basic pre-game setup
    preGame();
    
    // read the config info from the bootReader - 
    // We need to find a Competition and a set of PluginConfig instances
    Competition bootstrapCompetition = null;
    ArrayList<PluginConfig> configList = new ArrayList<PluginConfig>();
    //InputSource source = new InputSource(bootReader);
    XPathFactory factory = XPathFactory.newInstance();
    XPath xPath = factory.newXPath();
    try {
      // first grab the bootstrap offset
      XPathExpression exp =
          xPath.compile("/powertac-bootstrap-data/config/bootstrap-offset/@value");
      NodeList nodes = (NodeList)exp.evaluate(new InputSource(new FileReader(bootFile)),
                                              XPathConstants.NODESET);
      String value = nodes.item(0).getNodeValue();
      cc.setBootstrapDiscardedTimeslots(Integer.parseInt(value));
      log.info("offset: " + cc.getBootstrapDiscardedTimeslots() + " timeslots");
      
      // then pull out the Competition
      exp =
          xPath.compile("/powertac-bootstrap-data/config/competition");
      nodes = (NodeList)exp.evaluate(new InputSource(new FileReader(bootFile)),
                                     XPathConstants.NODESET);
      String xml = nodeToString(nodes.item(0));
      bootstrapCompetition = (Competition)messageConverter.fromXML(xml);
      
      // then get the configs
      exp = xPath.compile("/powertac-bootstrap-data/config/plugin-config");
      nodes = (NodeList)exp.evaluate(new InputSource(new FileReader(bootFile)),
                                     XPathConstants.NODESET);
      // Each node is a plugin-config
      for (int i = 0; i < nodes.getLength(); i++) {
        Node node = nodes.item(i);
        xml = nodeToString(node);
        PluginConfig pic = (PluginConfig)messageConverter.fromXML(xml);
        configList.add(pic);
      }
    }
    catch (XPathExpressionException xee) {
      log.error("preGame: Error reading config file: " + xee.toString());
      return false;
    }
    catch (IOException ioe) {
      log.error("preGame: Error opening file " + bootFile + ": " + ioe.toString());
    }
    // update the existing Competition - should be the current competition
    Competition.currentCompetition().update(bootstrapCompetition);
    
    // update the existing config, and make sure the bootReader has the
    // same set of PluginConfig instances as the running server
    for (Iterator<PluginConfig> pics = configList.iterator(); pics.hasNext(); ) {
      // find the matching one in the server and update it, then remove
      // the current element from the configList
      PluginConfig next = pics.next();
      PluginConfig match = pluginConfigRepo.findMatching(next);
      if (match == null) {
        // there's a pic in the file that's not in the server
        log.error("no matching PluginConfig found for " + next.toString());
        return false;
      }
      // if we found it, then we need to update it.
      match.update(next);
    }
    
    // we currently ignore cases where there's a config in the server that's
    // not in the file; there might be use cases for which this would
    // be useful.
    return true;
  }

  // method broken out to simplify testing
  void saveBootstrapData (Writer datasetWriter)
  {
    BufferedWriter output = new BufferedWriter(datasetWriter);
    List<Object> data = 
        defaultBroker.collectBootstrapData(competition.getBootstrapTimeslotCount());
    try {
      // write the config data
      output.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
      output.newLine();
      output.write("<powertac-bootstrap-data>");
      output.newLine();
      output.write("<config>");
      output.newLine();
      // bootstrap offset
      output.write("<bootstrap-offset value=\"" 
                   + cc.getBootstrapDiscardedTimeslots() + "\" />");
      output.newLine();
      // current competition
      output.write(messageConverter.toXML(competition));
      output.newLine();
      // next the PluginConfig instances
      for (PluginConfig pic : pluginConfigRepo.list()) {
        output.write(messageConverter.toXML(pic));
        output.newLine();
      }
      output.write("</config>");
      output.newLine();
      // finally the bootstrap data
      output.write("<bootstrap>");
      output.newLine();
      for (Object item : data) {
        output.write(messageConverter.toXML(item));
        output.newLine();
      }
      output.write("</bootstrap>");
      output.newLine();
      output.write("</powertac-bootstrap-data>");
      output.newLine();
      output.close();
    }
    catch (IOException ioe) {
      log.error("Error writing bootstrap file: " + ioe.toString());
    }
  }

  // Extracts a bootstrap dataset from its file
  private ArrayList<Object> processBootDataset (File datasetFile)
  {
    // Read and convert the bootstrap dataset
    ArrayList<Object> result = new ArrayList<Object>();
    XPathFactory factory = XPathFactory.newInstance();
    XPath xPath = factory.newXPath();
    try {
      InputSource source = new InputSource(new FileReader(datasetFile));
      // we want all the children of the bootstrap node
      XPathExpression exp =
          xPath.compile("/powertac-bootstrap-data/bootstrap/*");
      NodeList nodes = (NodeList)exp.evaluate(source, XPathConstants.NODESET);
      log.info("Found " + nodes.getLength() + " bootstrap nodes");
      // Each node is a bootstrap data item
      for (int i = 0; i < nodes.getLength(); i++) {
        String xml = nodeToString(nodes.item(i));
        Object msg = messageConverter.fromXML(xml);
        result.add(msg);
      }
    }
    catch (XPathExpressionException xee) {
      log.error("runOnce: Error reading config file: " + xee.toString());
    }
    catch (IOException ioe) {
      log.error("runOnce: reset fault: " + ioe.toString());
    }
    return result;
  }

  // Converts an xml node into a string that can be converted by XStream
  private String nodeToString(Node node) {
    StringWriter sw = new StringWriter();
    try {
      Transformer t = TransformerFactory.newInstance().newTransformer();
      t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      t.setOutputProperty(OutputKeys.INDENT, "no");
      t.transform(new DOMSource(node), new StreamResult(sw));
    }
    catch (TransformerException te) {
      log.error("nodeToString Transformer Exception " + te.toString());
    }
    String result = sw.toString();
    //log.info("xml node: " + result);
    return result;
  }
}
