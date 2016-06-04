package edu.duke.cs.osprey.tests;

import java.io.File;
import java.util.ArrayList;

import edu.duke.cs.osprey.astar.conf.ConfAStarNode;
import edu.duke.cs.osprey.astar.conf.ConfAStarTree;
import edu.duke.cs.osprey.astar.conf.ConfIndex;
import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.astar.conf.order.DynamicHMeanAStarOrder;
import edu.duke.cs.osprey.astar.conf.scoring.MPLPPairwiseHScorer;
import edu.duke.cs.osprey.astar.conf.scoring.PairwiseGScorer;
import edu.duke.cs.osprey.astar.conf.scoring.TraditionalPairwiseHScorer;
import edu.duke.cs.osprey.confspace.SearchProblem;
import edu.duke.cs.osprey.control.ConfigFileParser;
import edu.duke.cs.osprey.dof.deeper.DEEPerSettings;
import edu.duke.cs.osprey.ematrix.EnergyMatrix;
import edu.duke.cs.osprey.ematrix.EnergyMatrixCalculator;
import edu.duke.cs.osprey.ematrix.epic.EPICSettings;
import edu.duke.cs.osprey.energy.MultiTermEnergyFunction;
import edu.duke.cs.osprey.pruning.PruningMatrix;
import edu.duke.cs.osprey.tools.ObjectIO;

public class MPLPPLayground {

	public static void main(String[] args)
	throws Exception {
		
		// check the cwd
		String path = new File("").getAbsolutePath();
		if (!path.endsWith("test/DAGK")) {
			throw new Error("This profiler was designed to run in the test/DAGK folder\n\tcwd: " + path);
		}

		// load configuration
		ConfigFileParser cfp = new ConfigFileParser(new String[] {"-c", "KStar.cfg"});
		cfp.loadData();
		
		// multi-thread the energy function
		MultiTermEnergyFunction.setNumThreads(4);
		
		// init a conf space with lots of flexible residues, but no mutations
		final int NumFlexible = 16;
		ArrayList<String> flexRes = new ArrayList<>();
		ArrayList<ArrayList<String>> allowedAAs = new ArrayList<>();
		for (int i=0; i<NumFlexible; i++) {
			flexRes.add(Integer.toString(i + 1));
			allowedAAs.add(new ArrayList<String>());
		}
		boolean addWt = true;
		boolean doMinimize = true;
		boolean useEpic = false;
		boolean useTupleExpansion = false;
		boolean useEllipses = false;
		boolean useERef = false;
		boolean addResEntropy = false;
		boolean addWtRots = true;
		ArrayList<String[]> moveableStrands = new ArrayList<String[]>();
		ArrayList<String[]> freeBBZones = new ArrayList<String[]>();
		SearchProblem search = new SearchProblem(
			"energyMatrixProfiling",
			"2KDC.P.forOsprey.pdb", 
			flexRes, allowedAAs, addWt, doMinimize, useEpic, new EPICSettings(), useTupleExpansion,
			new DEEPerSettings(), moveableStrands, freeBBZones, useEllipses, useERef, addResEntropy, addWtRots
		);
		
		// compute the energy matrix
		File ematFile = new File(String.format("emat.min.%d.dat", NumFlexible));
		if (ematFile.exists()) {
			System.out.println("\nReading energy matrix...");
			search.emat = (EnergyMatrix)ObjectIO.readObject(ematFile.getAbsolutePath(), true);
		}
		if (search.emat == null) {
			System.out.println("\nComputing energy matrix...");
			EnergyMatrixCalculator emCalc = new EnergyMatrixCalculator(search.confSpace, search.shellResidues, useERef, addResEntropy);
			emCalc.calcPEM();
			search.emat = emCalc.getEMatrix();
			ObjectIO.writeObject(search.emat, ematFile.getAbsolutePath());
		}
		
		// don't bother with pruning, set all to unpruned
		search.pruneMat = new PruningMatrix(search.confSpace, search.emat.getPruningInterval());
		RCs rcs = new RCs(search.pruneMat);
		
		// define a partial conformation (set all flexible residues undefined for now)
		ConfIndex confIndex = new ConfIndex(NumFlexible);
		confIndex.setNumDefined(0);
		confIndex.setNumUndefined(NumFlexible);
		for (int i=0; i<NumFlexible; i++) {
			confIndex.getUndefinedPos()[i] = i;
		}
		
		// config the different heuristics
		TraditionalPairwiseHScorer tradHScorer = new TraditionalPairwiseHScorer(search.emat, rcs);
		MPLPPairwiseHScorer mplpHScorer = new MPLPPairwiseHScorer(search.emat);
		
		double tradHScore = tradHScorer.calc(confIndex, rcs);
		System.out.println(String.format("Trad H Score: %16.12f", tradHScore));
		
		double mplpHScore = mplpHScorer.calc(confIndex, rcs);
		System.out.println(String.format("MPLP H Score: %16.12f", mplpHScore));
		
		// get the real min bound conf
		ConfAStarTree tree = new ConfAStarTree(
			new DynamicHMeanAStarOrder(),
			new PairwiseGScorer(search.emat),
			new TraditionalPairwiseHScorer(search.emat, rcs),
			rcs
		);
		ConfAStarNode minBoundNode = tree.nextLeafNode();
		System.out.println(String.format("min bound energy: %16.12f", minBoundNode.getScore()));
		
		int[] minBoundConf = new int[NumFlexible];
		minBoundNode.getConf(minBoundConf);
		double minBoundMinimizedEnergy = search.minimizedEnergy(minBoundConf);
		System.out.println(String.format("min bound energy (minimized): %16.12f", minBoundMinimizedEnergy));
	}
}
