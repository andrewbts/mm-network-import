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

import java.io.File;
import java.io.IOException;

import netconfig.NetconfigException;
import core.DatabaseException;
import core.DatabaseReader;
import core.Monitor;
import edu.berkeley.path.model_elements.*;

/**
 * Main class for Mobile Millenium -> modelElements network importer
 * @author amoylan
 */
public class MMNetworkImport {
	
	private static String parentOutputDirectory = "output";

	/**
	 * Entry point: Script MM network import operations here and launch app.
	 * E.g., instantiate an ImportedNetwork, then serialize its various
	 * modelElements members. 
	 * @throws NetconfigException 
	 * @throws DatabaseException 
	 * @throws IOException 
	 */
	public static void main(String[] args) throws DatabaseException, NetconfigException, IOException {				
				
		// output directly into model-elements github layout at same level:
		parentOutputDirectory = "../model-elements/examples/mm-networks";
		
		// import from mmlivedb1, or from mmdevdb1?
		boolean importFromLive = false;
		
		if (importFromLive) {
			
			DatabaseReader db = new DatabaseReader("localhost", 5432, "live", "highway", "highwaymm");
					
	//		// smaller test networks
			importNetworkExportJson(28, 1, db);
			importNetworkExportJson(335, 1, db);
			
	//		// selected T01T02 networks
			importNetworkExportJson(179, 1, db);
			importNetworkExportJson(181, 1, db);
			importNetworkExportJson(183, 1, db);
			
	//		// from boris: example network with allocation matrices stored in DB (i15 ontario)
			importNetworkExportJson(249, 4015, db);
			
			db.close();
		}
		else {
			
			DatabaseReader db = new DatabaseReader("localhost", 5432, "dev", "highway", "highwaymm");
			
			// 880 networks
			importNetworkExportJson(344, 9174, db);
			importNetworkExportJson(345, 9175, db);
			importNetworkExportJson(346, 9176, db);
			importNetworkExportJson(343, 9173, db);
			importNetworkExportJson(342, 9162, db);
			importNetworkExportJson(347, 9177, db);
			
			// Ontario networks
			importNetworkExportJson(372, 9201, db);
			importNetworkExportJson(373, 9202, db);
			importNetworkExportJson(374, 9203, db);
			importNetworkExportJson(371, 9203, db);
			importNetworkExportJson(370, 9206, db);
			importNetworkExportJson(375, 9204, db);
			importNetworkExportJson(376, 9205, db);
			
			// Victorville networks
//			importNetworkExportJson(382, ????, db);
			importNetworkExportJson(383, 40005, db);
			importNetworkExportJson(384, 40005, db);
					
			db.close();	
			
		}
		
		Monitor.out("mm-network-import all done.\n");
		
	}
	
	private static void importNetworkExportJson(int nid, int cid, DatabaseReader db) throws DatabaseException, NetconfigException, IOException {
		
		ImportedNetwork imported = new ImportedNetwork(nid, cid, db);
		
		// create output directory for this nid
		String networkOutputDirectory = parentOutputDirectory + "/" + Integer.toString(nid);
		File dir = new File(networkOutputDirectory);
		dir.mkdirs();
		
		// write each model-elements object
		JsonHandler.writeToFile(imported.getConfig(), networkOutputDirectory + "/RunConfig.json");
		JsonHandler.writeToFile(imported.getSensorSet(), networkOutputDirectory + "/SensorSet.json");
		JsonHandler.writeToFile(imported.getNetwork(), networkOutputDirectory + "/Network.json");		
		JsonHandler.writeToFile(imported.getFundamentalDiagramMap(), networkOutputDirectory + "/FDMap.json");
		JsonHandler.writeToFile(imported.getOriginDemandMap(), networkOutputDirectory + "/DemandMap.json");
		JsonHandler.writeToFile(imported.getSplitRatioMap(), networkOutputDirectory + "/SplitRatioMap.json");
					
		Monitor.out("MM network " + nid + " written to directory " + dir.getCanonicalPath());
		Monitor.out("");		
		
	}

}
