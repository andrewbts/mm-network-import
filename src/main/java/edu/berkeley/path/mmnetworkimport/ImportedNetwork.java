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

import netconfig.NetconfigException;
import netconfig.Network;
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
		
		network = null;
		fundamentalDiagramMap = null;
		splitRatioMap = null;
		originDemandMap = null;
		
		// TODO: import network
		
		netconfig.Network mmnetwork = 
			new netconfig.Network(
					new DatabaseReader("localhost", 5432, "live", "highway", "highwaymm"), 
					Network.NetworkType.MODEL_GRAPH, 
					mm_nid); 		
		
		Monitor.out(mmnetwork.getLinks().length);
		
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
