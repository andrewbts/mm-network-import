/**
 * Copyright (c) 2012 The Regents of the University of California.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */

package edu.berkeley.path.mmnetworkimport;

import java.util.*;

import org.apache.commons.lang3.tuple.Pair;

// MM imports
import calibration.LinkFluxFuncParams;
import core.Coordinate;
import core.DatabaseException;
import core.DatabaseReader;
import core.Monitor;
import core.Time;
import netconfig.DataType;
import netconfig.ModelGraphLink;
import netconfig.ModelGraphNode;
import netconfig.NetconfigException;
import netconfig.TrafficFlowSink;
import netconfig.TrafficFlowSource;
import parameters.ConfigStore;
import parameters.EnkfNoiseParams;
import flowmodel.FlowModelNetwork;
import datatypes.State;

import edu.berkeley.path.model_elements.*;
import edu.berkeley.path.model_elements_base.EstimationForecastConfig;

/**
 * A collection of model-elements data structures constructed
 * from an MM network.
 * @author amoylan
 */
public class ImportedNetwork {
	
	private final RunConfig config;	
	private final Network network;
	private final FDMap fundamentalDiagramMap;
	private final SplitRatioMap splitRatioMap;
	private final DemandMap originDemandMap;	
	private final SensorSet sensorSet;
		
	/**
	 * Import network corresponding to the specified MM nid.
	 * @param mm_nid MM network table ID
	 * @param mm_cid MM configuration ID
	 * @throws NetconfigException 
	 * @throws DatabaseException 
	 */
	public ImportedNetwork(int mm_nid, int mm_cid, DatabaseReader db) throws DatabaseException, NetconfigException {						
		
		Monitor.out("Loading MM nid " + mm_nid + " from DB ...");
		
		// load Mobile Millenium network using netconfig library
		netconfig.Network mmnetwork = 
			new netconfig.Network(db, netconfig.Network.NetworkType.MODEL_GRAPH, mm_nid); 		
		
		State.Parameters mmparameters = ConfigStore.getParameters(db, mm_nid, mm_cid);
		
		// load pems sensor sets
		Monitor.set_nid(mm_nid); // workaround: getSensorsCached queries the global nid from Monitor so set it...
		netconfig.SensorPeMS[] mmpemssensors = DataType.PeMS.getSensorsCached(mmnetwork);
		
		Monitor.out("");
		Monitor.out("Importing MM nid " + mm_nid + " to model-elements format ...");
		
		// map from MM links, sources, and sinks, 
		// to list of model-elements links, one for each cell in the MM object (1 in case of source/sink)
		Map<Object, List<Link>> linkCellMap = new HashMap<Object, List<Link>>();
		// map from MM nodes to model-elements nodes
		Map<Object, Node> nodeMap = new HashMap<Object, Node>();
		
		List<Node> nodes = new ArrayList<Node>();
		List<Node> intermediateNodes = new ArrayList<Node>();
		List<Link> links = new ArrayList<Link>();
		Map<String, FD> linkFundamentalDiagramMap = new HashMap<String, FD>();
		Map<String, Map<String, Double>> originDemandFlowMap = new HashMap<String, Map<String, Double>>();
		
		// import nodes
		
		int maxNodeId = 0;		
		for (netconfig.ModelGraphNode mmnode : mmnetwork.getNodes()) {
			Node node = new Node();
			node.setId((long) mmnode.id);
			maxNodeId = Math.max(maxNodeId, mmnode.id);
			node.setLatitude(mmnode.getNavteqNode().geom.lat);
			node.setLongitude(mmnode.getNavteqNode().geom.lon);
//			Monitor.out("Imported node at lat=" + node.getLatitude() + ", long=" + node.getLongitude() + ".");
			node.setType("Freeway");
			node.setName(Integer.toString(mmnode.id));
			
			nodeMap.put(mmnode, node);
			nodes.add(node);
		}
		
		// generate unique new node IDs as needed
		int uniqueNodeId = maxNodeId;
		
		// import links, treating each MM cell as a separate link, and generating fundamental diagram map entries		
		int maxLinkId = 0;		
		for (netconfig.ModelGraphLink mmlink : mmnetwork.getLinks()) {
			Node startNode = nodeMap.get(mmlink.startNode);
			Node endNode;
			Link link;
			int linkid = mmlink.id * 100; // space out to get unique link ids
						
			// step through the cells inserting one link per cell, and interior nodes in between 
			int cellCount = mmlink.getNbCells();
			List<Link> cellLinks = new ArrayList<Link>(cellCount);
			double cellLength = mmlink.getLength() / mmlink.getNbCells();
			double speedLimit = mmlink.getAverageSpeedLimit();
			for (int i = 0; i < cellCount; ++i, ++linkid) {
								
				if (i == cellCount - 1) {
					// final cell
					endNode = nodeMap.get(mmlink.endNode);
				}
				else {									
					// new intermediate node
					endNode = new Node();
					endNode.setId((long) ++uniqueNodeId);
					endNode.setName(Integer.toString(uniqueNodeId) + " (near link " + linkid + ")");
					// TODO: set correct lat/long for intermediate node
					Coordinate interpolatedCoordinate = mmlink.getGeoMultiLine().getCoordinate((i + 1d) * cellLength);
					endNode.setLatitude(interpolatedCoordinate.lat);
					endNode.setLongitude(interpolatedCoordinate.lon);
//					Monitor.out("Created new intermediate node at lat=" + endNode.getLatitude() + ", long=" + endNode.getLongitude() + ".");
					endNode.setType("Freeway");
					nodes.add(endNode);
					intermediateNodes.add(endNode);
				}
				
				// create link
				link = new Link();
				link.setBegin(startNode);
				link.setEnd(endNode);
				link.setId((long) linkid);
				maxLinkId = Math.max(maxLinkId, linkid);
				link.setName(Integer.toString(linkid));
				// use center of this cell as the offset to estimate lane count:
				double cellCenterOffset = (i + 0.5d) * cellLength; 
				link.setLaneCount((double) mmlink.getNumLanesAtOffset((float) cellCenterOffset));				
				link.setLength(cellLength);				
				link.setSpeedLimit(speedLimit);								
				link.setType("Freeway");
				link.setLaneOffset(0); 
				link.setDetailLevel(1);
				link.setPointList(new ArrayList<Point>());
								
				links.add(link);				
				cellLinks.add(link);
								
				// create fundamental diagram map entry
				FD fd = new FD();				 
				Double shockSpeed = null, freeFlowSpeed = null, jamDensity = null, criticalSpeed = null;
				LinkFluxFuncParams fdParams = mmparameters.linkFluxFunction.get(mmlink);
				if (fdParams != null) {
					shockSpeed = fdParams.waveSpeed;
					freeFlowSpeed = fdParams.freeflowSpeed;
					jamDensity = fdParams.maxDensity;
					criticalSpeed = fdParams.criticalSpeed;
				}
				// hard coded default values from MM				
				if (shockSpeed == null) shockSpeed = 5.36448; 
				if (freeFlowSpeed == null) freeFlowSpeed = mmlink.getAverageSpeedLimit().doubleValue();
				if (jamDensity == null) jamDensity = 0.124300808 * link.getLaneCount();
				if (criticalSpeed == null) criticalSpeed = Math.min(26d, freeFlowSpeed - 2d); 
				fd.setCongestionWaveSpeed(Math.abs(shockSpeed));
				fd.setFreeFlowSpeed(freeFlowSpeed);				
				fd.setJamDensity(jamDensity);
				fd.setCriticalSpeed(criticalSpeed);								
				// derived quantity assuming smulder's flow model
				fd.setCapacity(criticalSpeed * jamDensity * (1d - criticalSpeed / freeFlowSpeed));
				fd.setCapacityDrop(0d);
				fd.setFreeFlowSpeedStd(0d);
				fd.setCapacityStd(0d);
				fd.setCongestionWaveSpeedStd(0d);
				
				linkFundamentalDiagramMap.put(link.getId().toString(), fd);
										
				startNode = endNode;								
			}						
			
			linkCellMap.put(mmlink, cellLinks);
		}
		
		Monitor.out(
				"Converted " + mmnetwork.getLinks().length + " MM links and " + 
				mmnetwork.getNodes().length + " MM nodes into " + links.size() +
				" links, " + nodes.size() + " nodes, and " + links.size() + 
				" fundamental diagram map entries ...");
		
		// generate unique new link IDs as needed
		int uniqueLinkId = maxLinkId;
		
		// import sources as origin links, and import their capacity into a demand map				
		for (netconfig.TrafficFlowSource mmsource : mmnetwork.getTrafficFlowSources()) {
			
			Node toNode = nodeMap.get(mmsource.node);
			
			// terminal source node
			Node originNode = new Node();
			originNode.setId((long) ++uniqueNodeId);
			originNode.setName(Integer.toString(uniqueNodeId));
			// use same lat/long as end node
			originNode.setLatitude(toNode.getLatitude());
			originNode.setLongitude(toNode.getLongitude());
			originNode.setType("Terminal");			
			nodes.add(originNode);
			nodeMap.put(mmsource, originNode);
			
			// origin link
			Link originLink = new Link();
			originLink.setBegin(originNode);			
			originLink.setEnd(toNode);
			originLink.setId((long) ++uniqueLinkId);
			originLink.setName(Integer.toString(uniqueLinkId));
			originLink.setType("Freeway");
			originLink.setLaneOffset(0); 
			originLink.setDetailLevel(1);
			// not applicable for origin links:
			originLink.setLength(0d);
			originLink.setLaneCount(0d); 
			originLink.setSpeedLimit(0d);
			originLink.setPointList(new ArrayList<Point>());
							
			links.add(originLink);
			linkCellMap.put(mmsource, createSingletonList(originLink));
			
			// demand map entry
			Map<String, Double> vehicleTypeMap = new HashMap<String, Double>();
			// put all capacity in single vehicle type "1":
			vehicleTypeMap.put("1", (double) mmsource.capacity); 
			originDemandFlowMap.put(Integer.toString(uniqueLinkId), vehicleTypeMap);
		}
						
		int added = mmnetwork.getTrafficFlowSources().length;
		Monitor.out(
				"Converted " + added + " MM sources into " +
				added + " origin links, " + 
				added + " terminal source nodes, and " +
				added + " origin demand map entries ...");				
		
		// import sinks as links and terminal nodes, and import their capacity into fundamental diagram map		
		for (netconfig.TrafficFlowSink mmsink : mmnetwork.getTrafficFlowSinks()) {
			
			Node fromNode = nodeMap.get(mmsink.node);
			
			// terminal end node
			Node terminalNode = new Node();
			terminalNode.setId((long) ++uniqueNodeId);
			terminalNode.setName(Integer.toString(uniqueNodeId));
			// use same lat/long as from node 
			terminalNode.setLatitude(fromNode.getLatitude());
			terminalNode.setLongitude(fromNode.getLongitude());
			terminalNode.setType("Terminal");
			
			nodes.add(terminalNode);
			nodeMap.put(mmsink, terminalNode);
			
			// sink link
			Link sinkLink = new Link();			
			sinkLink.setBegin(fromNode);			
			sinkLink.setEnd(terminalNode);						
			sinkLink.setId((long) ++uniqueLinkId);
			sinkLink.setName(Integer.toString(uniqueLinkId));									
			sinkLink.setType("Freeway");	
			// arbitrary plausible values for sink links:
			sinkLink.setLength(100d);			
			sinkLink.setSpeedLimit(20d);
			sinkLink.setLaneCount(1d);
			// not applicable for sink links:
			sinkLink.setLaneOffset(0);
			sinkLink.setDetailLevel(1);
			sinkLink.setPointList(new ArrayList<Point>());
			
			links.add(sinkLink);
			linkCellMap.put(mmsink, createSingletonList(sinkLink));
			
			// create fundamental diagram map entry
			FD fd = new FD();				 
			// arbitrary plausible values for sink links:
			fd.setFreeFlowSpeed(20d); // 45 mph			
			fd.setJamDensity(0.124300808d);
			fd.setCriticalSpeed(18d);
			fd.setCongestionWaveSpeed(5.36448d);
			// derived quantity assuming smulder's flow model
			fd.setCapacity(18d * 0.124300808d * (1d - 18d / 20d));
			fd.setCapacityDrop(0d);
			fd.setFreeFlowSpeedStd(0d);
			fd.setCapacityStd(0d);
			fd.setCongestionWaveSpeedStd(0d);
			
			linkFundamentalDiagramMap.put(sinkLink.getId().toString(), fd);
		}
				
		added = mmnetwork.getTrafficFlowSinks().length;
		Monitor.out(
				"Converted " + added + " MM sinks into " +
				added + " sink links, " + 
				added + " terminal nodes, and " +
				added + " fundamental diagram map entries ...");
		
		// finalise the network and resolve references, so it can be used below
		network = new Network();
		network.setId(0L);
		network.setName("MM nid " + Integer.toString(mm_nid));
		network.setDescription(
				"Mobile Millenium network " + Integer.toString(mm_nid) + 
				", imported from PostgreSQL by mm-network-import tool.");
		network.setLinkList(links);		
		network.setNodeList(nodes);		
		network.resolveReferences();
		
		// create split ratio maps corresponding to allocation matrices
		Map<String, Map<String, Map<String, Map<String, Double>>>> nodeSplitRatioMap = 
			new HashMap<String, Map<String, Map<String, Map<String, Double>>>>();
		
		// construct MM flow model network to re-use its code for automatic allocation matrix construction
		FlowModelNetwork mmflowmodel = new FlowModelNetwork(mmnetwork);		
		
		int allocationMatrixFromDB = 0, allocationMatrixAutoGenerated = 0;
		
		// MM nodes have a specified allocation matrix in the DB, else an automatically chosen default
		for (netconfig.ModelGraphNode mmnode : mmnetwork.getNodes()) {
			
			// get allocation matrix from DB if possible, else use MM auto-generated matrix
			double[][] allocationMatrix = mmparameters.allocationMatrix.get(mmnode.id);
			if (allocationMatrix == null) {
				allocationMatrix = mmflowmodel.getJunctionMap().get(mmnode.id).getAllocationMatrix();
				++allocationMatrixAutoGenerated;
			}
			else {
				++allocationMatrixFromDB;
			}
			
			Node node = nodeMap.get(mmnode);
			
			// convert double[][] matrix into map from link -> link -> vehicletype -> split ratio
			Map<String, Map<String, Map<String, Double>>> splitRatios = new HashMap<String, Map<String, Map<String, Double>>>();
			int in_index = -1;
			// rows of MM allocation matrix are ordered by links first, then sources
			for (ModelGraphLink mmInLink : mmnode.getInLinks()) {
				++in_index;				
				splitRatios.put(getLastElement(linkCellMap.get(mmInLink)).getId().toString(), 
						createSingleSplitRatio(allocationMatrix, in_index, mmnode, linkCellMap));
			}
			for (TrafficFlowSource mmsource : mmnode.getTrafficFlowSources()) {
				++in_index;				
				splitRatios.put(getLastElement(linkCellMap.get(mmsource)).getId().toString(), 
						createSingleSplitRatio(allocationMatrix, in_index, mmnode, linkCellMap));
			}
			nodeSplitRatioMap.put(node.getId().toString(), splitRatios);
		}								
		
		// intermediate nodes (from subdividing multi-celled links) all have single-in single-out allocation matrices
		for (Node node : intermediateNodes) {
			Map<String, Map<String, Map<String, Double>>> splitRatios = 
				createSingletonMap(node.getAllInLinks().iterator().next().getId().toString(),
						createSingletonMap(node.getAllOutLinks().iterator().next().getId().toString(),
								createSingletonMap("1", 1d)));
			nodeSplitRatioMap.put(node.getId().toString(), splitRatios);
		}
		
		Monitor.out("Converted " + allocationMatrixFromDB +
				" DB-specified node allocation matrices, " + 
				allocationMatrixAutoGenerated + " automatically generated node allocation matrices, and " +
				intermediateNodes.size() + " intermediate node trivial allocation matrices ...");
		
		// "null" allocation matrices for terminal (source and sink) nodes
		for (TrafficFlowSource mmsource : mmnetwork.getTrafficFlowSources()) {
			nodeSplitRatioMap.put(nodeMap.get(mmsource).getId().toString(), new HashMap<String, Map<String, Map<String, Double>>>());
		}
		for (netconfig.TrafficFlowSink mmsink : mmnetwork.getTrafficFlowSinks()) {
			nodeSplitRatioMap.put(nodeMap.get(mmsink).getId().toString(), new HashMap<String, Map<String, Map<String, Double>>>());
		}
		
		Monitor.out("Set empty allocation matrices for " + mmnetwork.getTrafficFlowSources().length +
				" origin (source) nodes and " + mmnetwork.getTrafficFlowSinks().length + " sink nodes ... ");
		
		// import pems sensor sets		
		List<Sensor> sensorList = new ArrayList<Sensor>(mmpemssensors.length);				
		for (netconfig.SensorPeMS mmsensor : mmpemssensors) {			
			Sensor sensor = new Sensor();
			sensor.setEntityId(Integer.toString(mmsensor.vdsID));
			sensor.setHealthStatus(mmsensor.isValid() ? 1d : 0d);
			sensor.setLaneNum((double) mmsensor.lane);
			Pair<Link, Double> localLinkOffset = getLinkCellOffset(linkCellMap.get(mmsensor.link), mmsensor.offset, mmsensor.link.length);
			sensor.setLinkId(localLinkOffset.getLeft().getId());
			sensor.setLinkOffset(localLinkOffset.getRight());
			sensor.setMeasurementFeedId("PeMS");
			sensor.setTypeEnum(SensorTypeEnum.LOOP);
			
			sensorList.add(sensor);			
		}
							
		Monitor.out("Converted " + mmpemssensors.length + " PeMS sensors ...");
			
		// create final model-elements objects
		
		config = new RunConfig();
		config.setEnkfConfig(EnKFConfig.createWithMMDefaults());		
		config.setRunModeEnum(RunMode.HISTORICAL);
		config.setCTMTypeEnum(CTMType.VELOCITY);	
		config.setEnsembleSize(150);
		config.setDtCTM(Duration.fromSeconds(mmnetwork.attributes.getReal("highway_timestep")));
		config.getEnkfConfig().setDtEnKF(Duration.fromSeconds(
				mmnetwork.attributes.getReal("highway_dataassimilation_timestep")));
		config.setLiveModeLag(Duration.fromSeconds(
				2.0d * mmnetwork.attributes.getReal("highway_dataassimilation_timestep")));
		config.setDtOutput(Duration.fromSeconds(
				mmnetwork.attributes.getReal("highway_dataassimilation_timestep"))); // using data assimilation time step as reporting time step too
		EnkfNoiseParams mmEnKFParams = EnkfNoiseParams.getEnkfNoiseParamsFromDB(db, mm_cid);
		config.setAdditiveModelNoiseMean(mmEnKFParams.modelNoiseMean);
		config.setAdditiveModelNoiseStdDev(mmEnKFParams.modelNoiseStdev);				
		config.getEnkfConfig().setNavteqNoiseMean(mmEnKFParams.navteqNoiseMean);
		config.getEnkfConfig().setNavteqNoiseStdev(mmEnKFParams.navteqNoiseStdev);
		config.getEnkfConfig().setTelenavNoiseMean(mmEnKFParams.telenavNoiseMean);
		config.getEnkfConfig().setTelenavNoiseStdev(mmEnKFParams.telenavNoiseStdev);
		config.getEnkfConfig().setEnkfTypeEnum(EnKFType.GLOBALJAMA);
		config.getEnkfConfig().setIncludePeMS(true);
		config.getEnkfConfig().setIncludeNavteq(false);
		config.getEnkfConfig().setIncludeTelenav(false);
		config.getEnkfConfig().setProbeProbabilityThreshold(0.7d);
		config.setEstimationForecastConfig(new EstimationForecastConfig());
		config.getEstimationForecastConfig().setForecastDuration(Duration.fromMilliseconds(1000 * 60 * 60 * 2)); // 2 hours
		config.getEstimationForecastConfig().setDtEstimationForecastSpinoff(Duration.fromMilliseconds(1000 * 60)); // 1 minute
		config.getEstimationForecastConfig().setDtEstimationForecastReport(Duration.fromMilliseconds(1000 * 60 * 5)); // 5 minutes
		config.setInitialDensityFraction(0.01d);		
		config.setFDTypeEnum(FDTypeEnum.SMULDERS);
		config.setAdditiveVelocityFunctionNoiseMean(0d);
		config.setAdditiveVelocityFunctionNoiseStdDev(0d);		
		config.setId(0L);
		config.setName("MM nid " + Integer.toString(mm_nid));
		Time startTime = mmnetwork.attributes.getTimestamp("starttime");
		Long startMilliseconds = (startTime == null ? 0 : startTime.getTimeInMillis());
		config.setTimeBegin(new DateTime(startMilliseconds));
		Time endTime = mmnetwork.attributes.getTimestamp("endtime");
		Long endMilliseconds = (endTime == null ? 0 : endTime.getTimeInMillis());
		config.setTimeEnd(new DateTime(endMilliseconds));
		config.setWorkflowEnum(Workflow.ESTIMATION);		
		config.setInitialEnsembleState(null);
		config.setInitialState(null);
		config.setInitialStateUncertainty(0d);
		config.setReportDirectory("reports");
		config.setReportEnsembleAfterCTM(true);
		config.setReportEnsembleAfterEnKF(false);
		config.setReportStatisticsAfterCTM(false);
		config.setReportStatisticsAfterEnKF(true);
		config.setReportStatisticsHistory(false);
		config.setReportToDB(true);
		config.setReportToDirectory(false);
		
		sensorSet = new SensorSet();
		sensorSet.setSensorList(sensorList);
		long sensorSetId = 10000 + mm_nid; // something likely unique
		sensorSet.setId(sensorSetId);
		sensorSet.setName(Long.toString(sensorSetId));
		sensorSet.setDescription("PeMS sensors for MM nid " + mm_nid + ", converted by mm-network-import");
		
		Monitor.out("Created config with duration " +  
				((endMilliseconds.doubleValue() - startMilliseconds.doubleValue()) / 1000d) + " sec, " +
				"CTM time step " + config.getDtCTM().getMilliseconds().doubleValue() / 1000d + " sec, " +
				"EnKF time step " + config.getEnkfConfig().getDtEnKF().getMilliseconds().doubleValue() / 1000d + " sec, and " +
				"output time step " + config.getDtOutput().getMilliseconds().doubleValue() / 1000d + " sec.");
		
		originDemandMap = new DemandMap();
		originDemandMap.setFlow(originDemandFlowMap);
		
		fundamentalDiagramMap = new FDMap();
		fundamentalDiagramMap.setFdMap(linkFundamentalDiagramMap);
		
		splitRatioMap = new SplitRatioMap();		
		splitRatioMap.setRatio(nodeSplitRatioMap );
		
		Monitor.out("");
				
	}
	
	/**
	 * Split ratio map out-link-id -> vehicle-type -> ratio, 
	 * for a single row of allocation matrix.
	 * @throws NetconfigException 
	 */
	private Map<String, Map<String, Double>> createSingleSplitRatio(
			double[][] allocationMatrix, int row, ModelGraphNode mmnode, Map<Object, List<Link>> linkCellMap) throws NetconfigException {
		Map<String, Map<String, Double>> splitRatio = new HashMap<String, Map<String, Double>>();
		int out_index = -1;
		// MM allocation matrix columns are ordered with out-links first, then sinks
		for (ModelGraphLink mmOutLink : mmnode.getOutLinks()) {
			++out_index;
			splitRatio.put(getFirstElement(linkCellMap.get(mmOutLink)).getId().toString(), this.<String, Double>createSingletonMap("1", allocationMatrix[out_index][row]));
		}
		for (TrafficFlowSink mmSink : mmnode.getTrafficFlowSinks()) {
			++out_index;
			splitRatio.put(getFirstElement(linkCellMap.get(mmSink)).getId().toString(), this.<String, Double>createSingletonMap("1", allocationMatrix[out_index][row]));
		}
		return splitRatio;
	}
	
	/**
	 * Find link with a sequence of links corresponding to a given overall offset. 
	 * Also return the local offset within the found link. 
	 * @param links List of links, assumed all the same length
	 * @param totalOffset Overall offset with sequence of links for which to find the link number and local offset
	 * @param totalLength Total length of all links (should equal total of lengths of elements of links parameter)
	 * @return Pair of link and local offset
	 */
	private Pair<Link, Double> getLinkCellOffset(List<Link> links, double totalOffset, double totalLength) {
		// assume all cells the same length
		int index = Math.max(Math.min(links.size() - 1,
				(int)(links.size() * totalOffset / totalLength)), 0);
		double cellSize = totalLength / links.size();
		
		double offset = totalOffset - index * cellSize;
		Link link = links.get(index);
		
		// Monitor.out(totalOffset + " / " + totalLength + " -> " + index + " / " + links.size() + " + " + offset);
		
		return Pair.of(link, offset);		
	}
	
	/**
	 * Create a map with a single entry of the form "key" -> value.
	 */
	private <K, V> Map<K, V> createSingletonMap(K key, V value) {
		Map<K, V> map = new HashMap<K, V>(1);
		map.put(key, value);
		return map;
	}
	
	/**
	 * Create singleton list (list with exactly one element)
	 */
	private <T> List<T> createSingletonList(T value) {
		List<T> singleton = new ArrayList<T>(1);
		singleton.add(value);
		return singleton;
	}
	
	private <T> T getLastElement(List<T> list) {
		return list.get(list.size() - 1);
	}
	
	private <T> T getFirstElement(List<T> list) {
		return list.get(0);
	}
	
	/**
	 * @return Model elements network
	 */
	public Network getNetwork() {
		return network;
	}

	/**
	 * @return Model elements fundamental diagram map giving FD for each link
	 */
	public FDMap getFundamentalDiagramMap() {
		return fundamentalDiagramMap;
	}

	/**
	 * @return Model elements split ratios map giving allocation matrix for each node
	 */
	public SplitRatioMap getSplitRatioMap() {
		return splitRatioMap;
	}

	/**
	 * @return Model elements origin demand map giving inptu demand for each origin link
	 */
	public DemandMap getOriginDemandMap() {
		return originDemandMap;
	}
	
	/**
	 * @return Freeway CTM+EnKF configuration representing corresponding MM settings 
	 */
	public RunConfig getConfig() {
		return config;
	}

	public SensorSet getSensorSet() {
		return sensorSet;
	}

}
