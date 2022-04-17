/*
** This file is part of OSPREY 3.0
** 
** OSPREY Protein Redesign Software Version 3.0
** Copyright (C) 2001-2018 Bruce Donald Lab, Duke University
** 
** OSPREY is free software: you can redistribute it and/or modify
** it under the terms of the GNU General Public License version 2
** as published by the Free Software Foundation.
** 
** You should have received a copy of the GNU General Public License
** along with OSPREY.  If not, see <http://www.gnu.org/licenses/>.
** 
** OSPREY relies on grants for its development, and since visibility
** in the scientific literature is essential for our success, we
** ask that users of OSPREY cite our papers. See the CITING_OSPREY
** document in this distribution for more information.
** 
** Contact Info:
**    Bruce Donald
**    Duke University
**    Department of Computer Science
**    Levine Science Research Center (LSRC)
**    Durham
**    NC 27708-0129
**    USA
**    e-mail: www.cs.duke.edu/brd/
** 
** <signature of Bruce Donald>, Mar 1, 2018
** Bruce Donald, Professor of Computer Science
*/

package edu.duke.cs.osprey.astar;

import edu.duke.cs.osprey.astar.conf.ConfAStarTree;
import edu.duke.cs.osprey.astar.conf.ConfRanker;
import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.confspace.ConfSearch;
import edu.duke.cs.osprey.confspace.Sequence;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.confspace.Strand;
import edu.duke.cs.osprey.ematrix.EnergyMatrix;
import edu.duke.cs.osprey.ematrix.NegatedEnergyMatrix;
import edu.duke.cs.osprey.ematrix.SimplerEnergyMatrixCalculator;
import edu.duke.cs.osprey.energy.ConfEnergyCalculator;
import edu.duke.cs.osprey.energy.EnergyCalculator;
import edu.duke.cs.osprey.energy.forcefield.ForcefieldParams;
import edu.duke.cs.osprey.parallelism.Parallelism;
import edu.duke.cs.osprey.restypes.ResidueTemplateLibrary;
import edu.duke.cs.osprey.structure.Molecule;
import edu.duke.cs.osprey.structure.PDBIO;
import edu.duke.cs.osprey.tools.FileTools;
import edu.duke.cs.osprey.tools.Stopwatch;
import edu.duke.cs.osprey.tools.TimeFormatter;

import java.io.File;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.function.Consumer;

import static edu.duke.cs.osprey.tools.Log.formatBig;
import static edu.duke.cs.osprey.tools.Log.log;
import static edu.duke.cs.osprey.tools.Log.appendToFile;
import static edu.duke.cs.osprey.tools.Log.deleteFile;

// Copied from BenchmarkConfRanker.java
public class AStarDebugger {

	public static void main(String[] args) {
		// TODO: GOAL: output all the conformations looked over in the tree search step

		/**
		 * Generate the tree
		 *
		 * WHILE ( there are more nodes left in the tree to iterate )
		 * DO
		 * 	print(its conformation)
		 * 	find energy of conformation
		 * ENDWHILE
		 */

		// also: figure out how to represent tree, so that the thing can be fed into python

		/**
		 * Pseudocode for A-star search:
		 * 	Q represents unexplored nodes sorted by increasing F score
		 * 	Q <- {root}
		 * 	WHILE Q is not empty
		 * 	DO
		 * 		node N <- pop(Q)
		 * 		YIELD N -> nextConf()
		 * 		calculate the true score of N
		 * 		FOR each child C of N
		 * 		DO
		 * 			calculate the F score of C
		 * 			place C in Q
		 * 	ENDWHILE
		 */

		/**
		 * Pseudocode for nextConf()
		 * Q <- new Queue {}
		 * FUNCTION nextConf():
		 *
		 * 	IF no root DO
		 * 		make root and add root to queue
		 * 	FI
		 *
		 * 	WHILE true DO // until we pop a leaf
		 * 		node N <- pop(Q)
		 * 		IF N is leaf DO
		 * 			return N
		 * 		ELSE DO // N has children
		 * 			add all of N's children to Q
		 * 		FI
		 * 	ENDWHILE
		 *
		 */

		/**
		 * Modifying the tree search for outputting the tree
		 *
		 * 	Q <- {root}
		 * 	log root
		 * 	WHILE Q is not empty
		 * 	DO
		 * 		node N <- pop(Q)
		 * 		calculate the true score of N
		 * 		log true score of N
		 * 		FOR each child C of N
		 * 		DO
		 * 			calculate the F score of C
		 * 			log "C <- N"
		 * 			place C in Q
		 * 	ENDWHILE
		 *
		 */


		/**
		 * Pseudocode for modified nextConf()
		 * Q <- new Queue {}
		 * FUNCTION nextConf():
		 *
		 * 	IF no root DO
		 * 		make root and add root to queue
		 * 	FI
		 *
		 * 	WHILE true DO // until we pop a leaf
		 * 		node N <- pop(Q)
		 * 		append information of N to output file
		 * 		IF N is leaf DO
		 * 			return N
		 * 		ELSE DO // N has children
		 * 			add all of N's children to Q
		 * 		FI
		 * 	ENDWHILE
		 *
		 */

		// try that gp120 design with massive flexibility
		Molecule mol = PDBIO.readResource("/gp120/gp120SRVRC26.09SR.pdb");

		ResidueTemplateLibrary templateLib = new ResidueTemplateLibrary.Builder() // This builds the "template". Is that like the "base"?
			.addMoleculeForWildTypeRotamers(mol)
			.addTemplates(FileTools.readResource("/gp120/all_nuc94_and_gr.in"))
			.addTemplateCoords(FileTools.readResource("/gp120/all_amino_coords.in"))
			.addRotamers(FileTools.readResource("/gp120/GenericRotamers.dat"))
			.build();

		Strand ligand = new Strand.Builder(mol)
			.setResidues("H1792", "L2250")
			.setTemplateLibrary(templateLib)
			.build();

		ligand.flexibility.get("H1901").setLibraryRotamers(Strand.WildType).addWildTypeRotamers().setContinuous();
		ligand.flexibility.get("H1904").setLibraryRotamers(Strand.WildType, "ALA", "VAL", "LEU", "ILE", "PHE", "TYR", "TRP", "CYS", "MET", "SER", "THR", "LYS", "ARG", "HIS", "ASP", "GLU", "ASN", "GLN", "GLY").addWildTypeRotamers().setContinuous();
		ligand.flexibility.get("H1905").setLibraryRotamers(Strand.WildType, "ALA", "VAL", "LEU", "ILE", "PHE", "TYR", "TRP", "CYS", "MET", "SER", "THR", "LYS", "ARG", "HIS", "ASP", "GLU", "ASN", "GLN", "GLY").addWildTypeRotamers().setContinuous();
		ligand.flexibility.get("H1906").setLibraryRotamers(Strand.WildType, "ALA", "VAL", "LEU", "ILE", "PHE", "TYR", "TRP", "CYS", "MET", "SER", "THR", "LYS", "ARG", "HIS", "ASP", "GLU", "ASN", "GLN", "GLY").addWildTypeRotamers().setContinuous();
//		ligand.flexibility.get("H1907").setLibraryRotamers(Strand.WildType, "ALA", "VAL", "LEU", "ILE", "PHE", "TYR", "TRP", "CYS", "MET", "SER", "THR", "LYS", "ARG", "HIS", "ASP", "GLU", "ASN", "GLN", "GLY").addWildTypeRotamers().setContinuous();
//
//		ligand.flexibility.get("H1908").setLibraryRotamers(Strand.WildType).addWildTypeRotamers().setContinuous();

//
//		Strand target = new Strand.Builder(mol)
//			.setResidues("F379", "J1791")
//			.setTemplateLibrary(templateLib)
//			.build();
//		target.flexibility.get("G973").setLibraryRotamers(Strand.WildType).addWildTypeRotamers().setContinuous();
//		target.flexibility.get("G977").setLibraryRotamers(Strand.WildType).addWildTypeRotamers().setContinuous();
//		target.flexibility.get("G978").setLibraryRotamers(Strand.WildType).addWildTypeRotamers().setContinuous();
//
		SimpleConfSpace confSpace = new SimpleConfSpace.Builder()
			.addStrand(ligand)
//			.addStrand(target)
			.build();

		// calc the emat
		EnergyMatrix emat;
		try (EnergyCalculator ecalc = new EnergyCalculator.Builder(confSpace, new ForcefieldParams())
			.setParallelism(Parallelism.makeCpu(4))
			.build()
		) {
			ConfEnergyCalculator confEcalc = new ConfEnergyCalculator.Builder(confSpace, ecalc).build();
			emat = new SimplerEnergyMatrixCalculator.Builder(confEcalc)
				.setCacheFile(new File("emat.gp120.complex.dat"))
				.build()
				.calcEnergyMatrix();
		}
		// pick the wild-type sequence
		Sequence sequence = confSpace.makeUnassignedSequence();
//		Sequence sequence = confSpace.makeWildTypeSequence();
		RCs rcs = sequence.makeRCs(confSpace);

		log("confs: %s", formatBig(makeAStar(emat, rcs).getNumConformations()));
//
//		// get the min,max scores
//		double minScore = makeAStar(emat, rcs)
//			.nextConf()
//			.getScore();
//		log("min score: %.4f", minScore);
//		double maxScore = -makeAStar(new NegatedEnergyMatrix(confSpace, emat), rcs)
//			.nextConf()
//			.getScore();
//		log("max score: %.4f", maxScore);
//
		// pick a few energy thresholds to rank, relative to the min score
		double[] energyOffsets = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };//, 11, 12 };//, 13, 14 };

		log("");

		// how quickly can we just enumerate the confs the regular way?
		{
			int counter = 0;
			ConfAStarTree astar = makeAStar(emat, rcs);
			double prev_energy = -999.;
			while (true) {

				ConfSearch.ScoredConf conf = astar.nextConf();
				if (conf == null) {
					break;
				}

				if(emat.confE(conf.getAssignments()) < prev_energy){
					log("Energy: " + emat.confE(conf.getAssignments()));
					log("This energy less than prev");
					prev_energy = emat.confE(conf.getAssignments());

					log("Score: " + conf.getScore());

					log(conf.toString());
				}

//				if(counter < 10){
//					log(Arrays.toString(conf.getAssignments()));
//				}
				counter++;
			}
		}
	}

	private static ConfAStarTree makeAStar(EnergyMatrix emat, RCs rcs) {
		ConfAStarTree astar = new ConfAStarTree.Builder(emat, rcs)
			.setTraditional()
			.build();
		astar.setParallelism(Parallelism.makeCpu(6));
		return astar;
	}
}