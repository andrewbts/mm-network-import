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

// MM imports
import calibration.LinkFluxFuncParams;
import core.DatabaseException;
import core.DatabaseReader;
import core.Monitor;
import core.Time;
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


/**
 * A collection of model-elements data structures constructed
 * from an MM network.
 * @author amoylan
 */
public class ImportedNetwork {
	
	private final FreewayContextConfig freewayContextConfig;	
	private final Network network;
	private final FDMap fundamentalDiagramMap;
	private final SplitRatioMap splitRatioMap;
	private final DemandMap originDemandMap;

	/**
	 * Import network corresponding to the specified MM nid.
	 * @param mm_nid MM network table ID
	 * @param mm_cid MM configuration ID
	 * @throws NetconfigException 
	 * @throws DatabaseException 
	 */
	public ImportedNetwork(int mm_nid, int mm_cid, DatabaseReader db) throws DatabaseException, NetconfigException {						
		
		// load Mobile Millenium network using netconfig library
		netconfig.Network mmnetwork = 
			new netconfig.Network(db, netconfig.Network.NetworkType.MODEL_GRAPH, mm_nid); 		
		
		State.Parameters mmparameters = ConfigStore.getParameters(mmnetwork, mm_cid);
		
		Monitor.out("");
		Monitor.out("Importing MM nid " + mm_nid + " to model-elements format ...");
		
		// map from MM objects to model-elements objects
		Map<Object, Link> linkMap = new HashMap<Object, Link>();
		Map<Object, Node> nodeMap = new HashMap<Object, Node>();
		
		List<Node> nodes = new ArrayList<Node>();
		List<Link> links = new ArrayList<Link>();
		Map<String, FD> linkFundamentalDiagramMap = new HashMap<String, FD>();
		Map<String, Map<String, Double>> originDemandFlowMap = new HashMap<String, Map<String, Double>>();
		
		// import nodes
		
		int maxNodeId = 0;		
		for (netconfig.ModelGraphNode mmnode : mmnetwork.getNodes()) {
			Node node = new Node();
			node.setId((long) mmnode.id);
			maxNodeId = Math.max(maxNodeId, mmnode.id);
			node.setType("Freeway");
			node.setName(Integer.toString(mmnode.id));
			
			nodeMap.put(mmnode, node);
			nodes.add(node);
		}
		
		// generate unique new node IDs as needed
		int uniqueNodeId = maxNodeId;
		
		// import links, treating each MM cell as a separate link, and generating fundamental diagram map entries		
		int maxLinkId = 0;		
		//Map<netconfig.ModelGraphLink, Link> linkMap = new HashMap<netconfig.ModelGraphLink, Link>();
		for (netconfig.ModelGraphLink mmlink : mmnetwork.getLinks()) {
			Node startNode = nodeMap.get(mmlink.startNode);
			Node endNode;
			Link link;
			int linkid = mmlink.id * 100; // space out to get unique link ids
			
			// step through the cells inserting one link per cell, and interior nodes in between 
			int cellCount = mmlink.getNbCells();
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
					endNode.setType("Freeway");
					nodes.add(endNode);
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
				// per alex k:
				link.setLaneOffset(0); 
				link.setDetailLevel(1);
								
				links.add(link);
				linkMap.put(mmlink, link);
				
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
				// per alex k:
				fd.setCapacityDrop(0d);
				fd.setFreeFlowSpeedStd(0d);
				fd.setCapacityStd(0d);
				fd.setCongestionWaveSpeedStd(0d);
				
				linkFundamentalDiagramMap.put(link.getId().toString(), fd);
										
				startNode = endNode;								
			}						
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
			// terminal source node
			Node originNode = new Node();
			originNode.setId((long) ++uniqueNodeId);
			originNode.setName(Integer.toString(uniqueNodeId));
			originNode.setType("Terminal");
			
			nodes.add(originNode);
			nodeMap.put(mmsource, originNode);
			
			// origin link
			Link originLink = new Link();
			originLink.setBegin(originNode);
			Node toNode = nodeMap.get(mmsource.node);
			originLink.setEnd(toNode);
			originLink.setId((long) ++uniqueLinkId);
			originLink.setName(Integer.toString(uniqueLinkId));
			originLink.setType("Freeway");
			// per alex k:
			originLink.setLaneOffset(0); 
			originLink.setDetailLevel(1);
			// not applicable for origin links:
			originLink.setLength(0d);
			originLink.setLaneCount(0d); 
			originLink.setSpeedLimit(0d);
							
			links.add(originLink);
			linkMap.put(mmsource, originLink);
			
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
			// terminal end node
			Node terminalNode = new Node();
			terminalNode.setId((long) ++uniqueNodeId);
			terminalNode.setName(Integer.toString(uniqueNodeId));
			terminalNode.setType("Terminal");
			
			nodes.add(terminalNode);
			nodeMap.put(mmsink, terminalNode);
			
			// sink link
			Link sinkLink = new Link();
			Node fromNode = nodeMap.get(mmsink.node);
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
			
			links.add(sinkLink);
			linkMap.put(mmsink, sinkLink);
			
			// create fundamental diagram map entry
			FD fd = new FD();				 
			// arbitrary plausible values for sink links:
			fd.setFreeFlowSpeed(20d); // 45 mph			
			fd.setJamDensity(0.124300808d);
			fd.setCriticalSpeed(18d);
			fd.setCongestionWaveSpeed(5.36448d);
			// derived quantity assuming smulder's flow model
			fd.setCapacity(18d * 0.124300808d * (1d - 18d / 20d));
			// per alex k:
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
		
		// create split ratio maps corresponding to allocation matrices
		Map<String, Map<String, Map<String, Map<String, Double>>>> nodeSplitRatioMap = 
			new HashMap<String, Map<String, Map<String, Map<String, Double>>>>();
		
		// construct MM flow model network to re-use its code for automatic allocation matrix construction
		FlowModelNetwork mmflowmodel = new FlowModelNetwork(mmnetwork);		
		int allocationMatrixFromDB = 0, allocationMatrixAutoGenerated = 0;
		// MM nodes have a specified allocation matrix or automatically chosen default
		for (netconfig.ModelGraphNode mmnode : mmnetwork.getNodes()) {
			
			// get allocation matrix from DB if possible, else use MM auto-generated matrix
			double[][] allocationMatrix = mmparameters.allocationMatrix.get(mmnode);
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
				splitRatios.put(linkMap.get(mmInLink).getId().toString(), 
						createSingleSplitRatio(allocationMatrix, in_index, mmnode, linkMap));
			}
			for (TrafficFlowSource mmsource : mmnode.getTrafficFlowSources()) {
				++in_index;				
				splitRatios.put(linkMap.get(mmsource).getId().toString(), 
						createSingleSplitRatio(allocationMatrix, in_index, mmnode, linkMap));
			}
			nodeSplitRatioMap.put(node.getId().toString(), splitRatios);
		}								
		
		Monitor.out("Converted " + allocationMatrixFromDB +
				" DB-specified node allocation matrices, and " + 
				allocationMatrixAutoGenerated + " automatically generated node allocation matrices ...");
		
		// "null" allocation matrices for terminal (source and sink) nodes
		for (TrafficFlowSource mmsource : mmnetwork.getTrafficFlowSources()) {
			nodeSplitRatioMap.put(nodeMap.get(mmsource).getId().toString(), new HashMap<String, Map<String, Map<String, Double>>>());
		}
		for (netconfig.TrafficFlowSink mmsink : mmnetwork.getTrafficFlowSinks()) {
			nodeSplitRatioMap.put(nodeMap.get(mmsink).getId().toString(), new HashMap<String, Map<String, Map<String, Double>>>());
		}
		
		Monitor.out("Set empty allocation matrices for " + mmnetwork.getTrafficFlowSources().length +
				" origin (source) nodes and " + mmnetwork.getTrafficFlowSinks().length + " sink nodes ... ");
			
		// create final model-elements objects
		
		freewayContextConfig = new FreewayContextConfig();
		freewayContextConfig.setCTMTypeEnum(CTMType.VELOCITY);	
		freewayContextConfig.setDt(Duration.fromSeconds(mmnetwork.attributes.getReal("highway_timestep")));
		freewayContextConfig.setDtOutput(Duration.fromSeconds(
				mmnetwork.attributes.getReal("highway_dataassimilation_timestep"))); // assuming these are the same thing
		EnkfNoiseParams mmEnKFParams = EnkfNoiseParams.getEnkfNoiseParamsFromDB(db, mm_cid);
		freewayContextConfig.setAdditiveModelNoiseMean(mmEnKFParams.modelNoiseMean);
		freewayContextConfig.setAdditiveModelNoiseStdDev(mmEnKFParams.modelNoiseStdev);
		freewayContextConfig.setEnkfParams(EnKFParams.createWithMMDefaults());
		freewayContextConfig.getEnkfParams().setModelNoiseMean(mmEnKFParams.modelNoiseMean);
		freewayContextConfig.getEnkfParams().setModelNoiseStdev(mmEnKFParams.modelNoiseStdev);
		freewayContextConfig.getEnkfParams().setNavteqNoiseMean(mmEnKFParams.navteqNoiseMean);
		freewayContextConfig.getEnkfParams().setNavteqNoiseStdev(mmEnKFParams.navteqNoiseStdev);
		freewayContextConfig.getEnkfParams().setTelenavNoiseMean(mmEnKFParams.telenavNoiseMean);
		freewayContextConfig.getEnkfParams().setTelenavNoiseStdev(mmEnKFParams.telenavNoiseStdev);
		freewayContextConfig.setEnkfTypeEnum(EnKFType.GLOBALJAMA);
		freewayContextConfig.setFDTypeEnum(FDTypeEnum.SMULDERS);
		freewayContextConfig.setRunModeEnum(RunMode.HISTORICAL);
		freewayContextConfig.setId(0L);
		freewayContextConfig.setName("MM nid " + Integer.toString(mm_nid));
		Time startTime = mmnetwork.attributes.getTimestamp("starttime");
		Long startMilliseconds = (startTime == null ? 0 : startTime.getTimeInMillis());
		freewayContextConfig.setTimeBegin(new DateTime(startMilliseconds));
		Time endTime = mmnetwork.attributes.getTimestamp("endtime");
		Long endMilliseconds = (endTime == null ? 0 : endTime.getTimeInMillis());
		freewayContextConfig.setTimeEnd(new DateTime(endMilliseconds));
		freewayContextConfig.setWorkflowEnum(Workflow.ESTIMATION);		
		
		Monitor.out("Created config with duration " +  
				((endMilliseconds.doubleValue() - startMilliseconds.doubleValue()) / 1000d) + " sec, " +
				"time step " + freewayContextConfig.getDt().getMilliseconds().doubleValue() / 1000d + " sec, and " +
				"output time step " + freewayContextConfig.getDtOutput().getMilliseconds().doubleValue() / 1000d + " sec.");
		
		network = new Network();
		network.setId(0L);
		network.setName("MM nid " + Integer.toString(mm_nid));
		network.setDescription(
				"Mobile Millenium network " + Integer.toString(mm_nid) + 
				", imported from PostgreSQL by mm-network-import tool.");
		network.setLinkList(links);		
		network.setNodeList(nodes);		
		network.resolveReferences();
		
		originDemandMap = new DemandMap();
		originDemandMap.setFlowMap(originDemandFlowMap);
		
		fundamentalDiagramMap = new FDMap();
		fundamentalDiagramMap.setFdMap(linkFundamentalDiagramMap);
		
		splitRatioMap = new SplitRatioMap();		
		splitRatioMap.setRatioMap(nodeSplitRatioMap );
		
		Monitor.out("\n");
				
	}
	
	/**
	 * Split ratio map out-link-id -> vehicle-type -> ratio, 
	 * for a single row of allocation matrix.
	 * @throws NetconfigException 
	 */
	private Map<String, Map<String, Double>> createSingleSplitRatio(
			double[][] allocationMatrix, int row, ModelGraphNode mmnode, Map<Object, Link> linkMap) throws NetconfigException {
		Map<String, Map<String, Double>> splitRatio = new HashMap<String, Map<String, Double>>();
		int out_index = -1;
		// MM allocation matrix columns are ordered with out-links first, then sinks
		for (ModelGraphLink mmOutLink : mmnode.getOutLinks()) {
			++out_index;
			splitRatio.put(linkMap.get(mmOutLink).getId().toString(), createSingletonMap("1", allocationMatrix[out_index][row]));
		}
		for (TrafficFlowSink mmSink : mmnode.getTrafficFlowSinks()) {
			++out_index;
			splitRatio.put(linkMap.get(mmSink).getId().toString(), createSingletonMap("1", allocationMatrix[out_index][row]));
		}
		return splitRatio;
	}
	
	/**
	 * Create a map with a single entry of the form "key" -> value (double).
	 */
	private Map<String, Double> createSingletonMap(String key, Double value) {
		Map<String, Double> map = new HashMap<String, Double>(1);
		map.put(key, value);
		return map;
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
	public FreewayContextConfig getFreewayContextConfig() {
		return freewayContextConfig;
	}
	
}
