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

import calibration.LinkFluxFuncParams;
import celltransmissionmodel.velocity.VelocityJunctionSolver;

import netconfig.NetconfigException;
import parameters.ConfigStore;

import core.DatabaseException;
import core.DatabaseReader;
import core.Monitor;
import datatypes.State;
import edu.berkeley.path.model_elements.*;

/**
 * A collection of model-elements data structures constructed
 * from an MM network.
 * @author amoylan
 */
public class ImportedNetwork {
	
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
		
		// import nodes
		List<Node> nodes = new ArrayList<Node>();
		int maxNodeId = 0;
		Map<netconfig.ModelGraphNode, Node> nodeMap = new HashMap<netconfig.ModelGraphNode, Node>();
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
		List<Link> links = new ArrayList<Link>();
		int maxLinkId = 0;
		Map<String, FD> linkFundamentalDiagramMap = new HashMap<String, FD>();
		//Map<netconfig.ModelGraphLink, Link> linkMap = new HashMap<netconfig.ModelGraphLink, Link>();
		for (netconfig.ModelGraphLink mmlink : mmnetwork.getLinks()) {
			Node startNode = nodeMap.get(mmlink.startNode);
			Node endNode;
			Link link;
			int linkid = mmlink.id * 100; // space out to get unique link ids
			
			// step through the cells inserting one link per cell, and interior nodes in between 
			int cellCount = mmlink.getNbCells();
			double cellLength = mmlink.getLength() / mmlink.getNbCells();
			int speedLimit = Math.round(mmlink.getAverageSpeedLimit()); // TODO: why integer?
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
				link.setSpeedLimit(speedLimit); // TODO: why integer?								
				link.setType("Freeway"); // TODO: what are valid types?
				// per alex k:
				link.setLaneOffset(0); 
				link.setDetailLevel(1);
								
				links.add(link);
				
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
		Map<String, Map<String, Double>> originDemandFlowMap = new HashMap<String, Map<String, Double>>();
		for (netconfig.TrafficFlowSource mmsource : mmnetwork.getTrafficFlowSources()) {
			// terminal source node
			Node originNode = new Node();
			originNode.setId((long) ++uniqueNodeId);
			originNode.setName(Integer.toString(uniqueNodeId));
			originNode.setType("Terminal");
			
			nodes.add(originNode);
			
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
			originLink.setSpeedLimit(0);
							
			links.add(originLink);
			
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
			sinkLink.setSpeedLimit(20);
			sinkLink.setLaneCount(1d);
			// not applicable for sink links:
			sinkLink.setLaneOffset(0);
			sinkLink.setDetailLevel(1);
			
			links.add(sinkLink);
			
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
			
		// create final model-elements objects
		
		network = new Network();
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
		
		
				
		db.close();		
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
	
}
