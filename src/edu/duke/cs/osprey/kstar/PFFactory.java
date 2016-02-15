package edu.duke.cs.osprey.kstar;

import java.util.ArrayList;

import edu.duke.cs.osprey.confspace.SearchProblem;
import edu.duke.cs.osprey.control.ConfigFileParser;
import edu.duke.cs.osprey.dof.deeper.DEEPerSettings;
import edu.duke.cs.osprey.kstar.remote.PFMNPCPMCache;
import edu.duke.cs.osprey.pruning.PruningControl;


/**
 * 
 * @author Adegoke Ojewole (ao68@duke.edu)
 * 
 * Chooses the type of partition function implementation, depending upon user
 * supplied value
 */
public class PFFactory {

	public static PFAbstract getPartitionFunction( String implementation,
			ArrayList<String> sequence, 
			ConfigFileParser cfp, SearchProblem sp, PruningControl pc, 
			DEEPerSettings dset, ArrayList<String[]> moveableStrands, ArrayList<String[]> freeBBZones,
			double EW_I0 ) {

		switch( implementation.toLowerCase() ) {

		case "1nnocache":
			return new PF1NNoCache( sequence, cfp, sp, pc, dset, moveableStrands, freeBBZones, EW_I0 );

		case "1nastar":
			return new PF1NAStar( sequence, cfp, sp, pc, dset, moveableStrands, freeBBZones, EW_I0 );
			
		case "1npmcache":
			return new PF1NPMCache( sequence, cfp, sp, pc, dset, moveableStrands, freeBBZones, EW_I0 );

		case "1npcpmcache":
			return new PF1NPCPMCache( sequence, cfp, sp, pc, dset, moveableStrands, freeBBZones, EW_I0 );

		case "1nmtpcpmcache":
			return new PF1NMTPCPMCache( sequence, cfp, sp, pc, dset, moveableStrands, freeBBZones, EW_I0 );

		case "mnpcpmcache":
			return new PFMNPCPMCache( sequence, cfp, sp, pc, dset, moveableStrands, freeBBZones, EW_I0 );

		default:
			throw new RuntimeException("ERROR: specified value of parameter pFuncMethod is invalid");

		}
	}

}
