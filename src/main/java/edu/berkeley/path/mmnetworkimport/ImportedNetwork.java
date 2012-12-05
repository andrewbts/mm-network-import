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

import netconfig.NetconfigException;
import core.DatabaseException;
import core.DatabaseReader;
import core.Monitor;
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
	 * @throws NetconfigException 
	 * @throws DatabaseException 
	 */
	public ImportedNetwork(int mm_nid) throws DatabaseException, NetconfigException {
		
		fundamentalDiagramMap = null;
		splitRatioMap = null;
		originDemandMap = null;
		
		// connect via localhost (change as needed)
		DatabaseReader db = new DatabaseReader("localhost", 5432, "live", "highway", "highwaymm");
		
		// load Mobile Millenium network using netconfig library
		netconfig.Network mmnetwork = 
			new netconfig.Network(db, netconfig.Network.NetworkType.MODEL_GRAPH, mm_nid); 		
		
		Monitor.out("");
		Monitor.out("Importing nid " + mm_nid + " with " + mmnetwork.getLinks().length + 
				" links and " + mmnetwork.getNodes().length + " nodes to modelElements format ...");
		
		// import nodes
		List<Node> nodes = new ArrayList<Node>();
		int maxNodeId = 0;
		Map<netconfig.ModelGraphNode, Node> nodeMap = new HashMap<netconfig.ModelGraphNode, Node>();
		for (netconfig.ModelGraphNode mmnode : mmnetwork.getNodes()) {
			Node node = new Node();
			node.setId((long) mmnode.id);
			maxNodeId = Math.max(maxNodeId, mmnode.id);
			node.setType("Highway");
			node.setName(Integer.toString(mmnode.id));
			nodeMap.put(mmnode, node);
		}
		
		// generate unique new node IDs as needed
		int uniqueNodeId = maxNodeId + 1;
		
		// import links, treating each MM cell as a separate link
		List<Link> links = new ArrayList<Link>();
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
					endNode.setId((long) uniqueNodeId++);
					endNode.setName(Integer.toString(uniqueNodeId));
					endNode.setType("Highway");
					nodes.add(endNode);
				}
				
				link = new Link();
				link.setBegin(startNode);
				link.setEnd(endNode);
				link.setId((long) linkid);
				link.setDetailLevel(0); // TODO: what is this?
				link.setLength(cellLength);
				link.setName(Integer.toString(linkid));
				link.setSpeedLimit(speedLimit); // TODO: why integer?
				double offset = (i + 0.5d) * cellLength; // use center of this cell as the offset
				link.setLaneCount((double) mmlink.getNumLanesAtOffset((float) offset));
				link.setType("?"); // TODO: what are valid types?
				link.setLaneOffset(0);
				
				links.add(link);
				
				startNode = endNode;								
			}						
		}
				
		// TODO: assign integer IDs (and names) to all nodes that don't have one yet
		
		
		network = new Network();
		network.setName("MM nid " + Integer.toString(mm_nid));
		network.setDescription(
				"Mobile Millienium network " + Integer.toString(mm_nid) + 
				", imported from PostgreSQL by mmnetworkimport tool.");
		network.setLinkList(links);		
		network.setNodeList(nodes);		
		
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
