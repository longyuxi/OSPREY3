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

package edu.duke.cs.osprey.kstar;

import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.confspace.ConfDB;
import edu.duke.cs.osprey.confspace.ConfSearch;
import edu.duke.cs.osprey.confspace.Sequence;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.energy.ConfEnergyCalculator;
import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;
import edu.duke.cs.osprey.kstar.pfunc.PartitionFunction;
import edu.duke.cs.osprey.tools.MathTools;

import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of the K* algorithm to predict protein sequence mutations that improve
 * binding affinity by computing provably accurate Boltzmann-weighted ensembles
 * {@cite Lilien2008 Ryan H. Lilien, Brian W. Stevens, Amy C. Anderson, and Bruce R. Donald, 2005.
 * A Novel Ensemble-Based Scoring and Search Algorithm for Protein Redesign and Its Application
 * to Modify the Substrate Specificity of the Gramicidin Synthetase A Phenylalanine Adenylation Enzyme
 * In Journal of Computational Biology (vol 12. num. 6 pp. 740–761).}.
 */
public class KStar {

	// *sigh* Java makes this stuff so verbose to do...
	// Kotlin would make this so much easier
	public static class Settings {

		public static class Builder {

			/**
			 * Value of epsilon in (0,1] for the epsilon-approximation to a partition function.
			 *
			 * Smaller values for epsilon yield more accurate predictions, but can take
			 * longer to run.
			 */
			private double epsilon = 0.683;

			/**
			 * Pruning criteria to remove sequences with unstable unbound states relative to the wild type sequence.
			 * Defined in units of kcal/mol.
			 *
			 * More precisely, a sequence is pruned when the following expression is true:
			 *
			 * U(Z_s) < L(W_s) * B(t)
			 *
			 * where:
			 *   - s represents the unbound protein strand, or unbound ligand strand
			 *   - U(Z_s) is the upper bound on the partition function for strand s
			 *   - L(W_s) is the lower bound on the partition function for strand s in the wild type
			 *   - t is the stability threshold
			 *   - B() is the Boltzmann weighting function
			 *
			 * Set to null to disable the filter entirely.
			 */
			private Double stabilityThreshold = 5.0;

			/** The maximum number of simultaneous residue mutations to consider for each sequence mutant */
			private int maxSimultaneousMutations = 1;

			private KStarScoreWriter.Writers scoreWriters = new KStarScoreWriter.Writers();

			/**
			 * If true, prints out information to the console for each minimized conformation during
			 * partition function approximation
			 */
			private boolean showPfuncProgress = false;

			/**
			 * If a design experiences an unexpected abort, the conformation database can allow you to restore the
			 * design state and resume the calculation close to where it was aborted.
			 * Set a pattern to turn on the conf DB such as:
			 *
			 * "theFolder/conf.*.db"
			 *
			 * The * in the pattern is a wildcard character that will be replaced with
			 * each type of energy matrix used by the K*-type algorithm.
			 */
			private String confDBPattern = null;

			/**
			 * True to use external memory when buffering conformations between the
			 * partition function lower and upper bound calculators.
			 */
			private boolean useExternalMemory = false;

			public Builder setEpsilon(double val) {
				epsilon = val;
				return this;
			}

			public Builder setStabilityThreshold(Double val) {
				if (val != null && val.isInfinite()) {
					throw new IllegalArgumentException("only finite values allowed. To turn off the filter, pass null");
				}
				stabilityThreshold = val;
				return this;
			}

			public Builder setMaxSimultaneousMutations(int val) {
				maxSimultaneousMutations = val;
				return this;
			}

			public Builder addScoreWriter(KStarScoreWriter val) {
				scoreWriters.add(val);
				return this;
			}

			public Builder addScoreConsoleWriter(KStarScoreWriter.Formatter val) {
				return addScoreWriter(new KStarScoreWriter.ToConsole(val));
			}

			public Builder addScoreConsoleWriter() {
				return addScoreConsoleWriter(new KStarScoreWriter.Formatter.SequenceKStarPfuncs());
			}

			public Builder addScoreFileWriter(File file, KStarScoreWriter.Formatter val) {
				return addScoreWriter(new KStarScoreWriter.ToFile(file, val));
			}

			public Builder addScoreFileWriter(File file) {
				return addScoreFileWriter(file, new KStarScoreWriter.Formatter.Log());
			}

			public Builder setShowPfuncProgress(boolean val) {
				showPfuncProgress = val;
				return this;
			}

			public Builder setConfDBPattern(String val) {
				confDBPattern = val;
				return this;
			}

			public Builder setExternalMemory(boolean val) {
				useExternalMemory = val;
				return this;
			}

			public Settings build() {
				return new Settings(epsilon, stabilityThreshold, maxSimultaneousMutations, scoreWriters, showPfuncProgress, confDBPattern, useExternalMemory);
			}
		}

		public final double epsilon;
		public final Double stabilityThreshold;
		public final int maxSimultaneousMutations;
		public final KStarScoreWriter.Writers scoreWriters;
		public final boolean showPfuncProgress;
		public final String confDBPattern;
		public final boolean useExternalMemory;


		public Settings(double epsilon, Double stabilityThreshold, int maxSimultaneousMutations, KStarScoreWriter.Writers scoreWriters, boolean dumpPfuncConfs, String confDBPattern, boolean useExternalMemory) {
			this.epsilon = epsilon;
			this.stabilityThreshold = stabilityThreshold;
			this.maxSimultaneousMutations = maxSimultaneousMutations;
			this.scoreWriters = scoreWriters;
			this.showPfuncProgress = dumpPfuncConfs;
			this.confDBPattern = confDBPattern;
			this.useExternalMemory = useExternalMemory;
		}

		public String applyConfDBPattern(String type) {

			// the pattern has a * right?
			if (confDBPattern.indexOf('*') < 0) {
				throw new IllegalArgumentException("confDBPattern (which is '" + confDBPattern + "') has no wildcard character (which is *)");
			}

			return confDBPattern.replace("*", type);
		}
	}

	public static class ScoredSequence {

		public final Sequence sequence;
		public final KStarScore score;

		public ScoredSequence(Sequence sequence, KStarScore score) {
			this.sequence = sequence;
			this.score = score;
		}

		@Override
		public String toString() {
			return "sequence: " + sequence + "   K*(log10): " + score;
		}

		public String toString(Sequence wildtype) {
			return "sequence: " + sequence.toString(Sequence.Renderer.AssignmentMutations) + "   K*(log10): " + score;
		}
	}

	public static enum ConfSpaceType {
		Protein,
		Ligand,
		Complex
	}

	public static class InitException extends RuntimeException {

		public InitException(ConfSpaceType type, String name) {
			super(String.format("set %s for the %s conf space info before running", name, type.name()));
		}
	}

	public static interface ConfSearchFactory {
		public ConfSearch make(RCs rcs);
	}

	public class ConfSpaceInfo {

		public final SimpleConfSpace confSpace;
		public final ConfSpaceType type;
		public final String id;

		public final List<Sequence> sequences = new ArrayList<>();
		public final Map<Sequence,PartitionFunction.Result> pfuncResults = new HashMap<>();

		public ConfEnergyCalculator confEcalc = null;
		public ConfSearchFactory confSearchFactory = null;

		public ConfSpaceInfo(SimpleConfSpace confSpace, ConfSpaceType type) {
			this.confSpace = confSpace;
			this.type = type;
			this.id = type.name().toLowerCase();
		}

		private void check() {
			if (confEcalc == null) {
				throw new InitException(type, "confEcalc");
			}
			if (confSearchFactory == null) {
				throw new InitException(type, "confSearchFactory");
			}
		}

		public void clear() {
			sequences.clear();
			pfuncResults.clear();
		}

		public PartitionFunction.Result calcPfunc(int sequenceIndex, BigDecimal stabilityThreshold, ConfDB confDB) {

			Sequence sequence = sequences.get(sequenceIndex);

			// check the cache first
			PartitionFunction.Result result = pfuncResults.get(sequence);
			if (result != null) {
				return result;
			}

			// cache miss, need to compute the partition function

			// make the partition function
			PartitionFunction pfunc = PartitionFunction.makeBestFor(confEcalc);
			pfunc.setReportProgress(settings.showPfuncProgress);
			if (confDB != null) {
				PartitionFunction.WithConfTable.setOrThrow(pfunc, confDB.getSequence(sequence));
			}
			RCs rcs = sequence.makeRCs();
			if (settings.useExternalMemory) {
				PartitionFunction.WithExternalMemory.setOrThrow(pfunc, true, rcs);
			}
			ConfSearch astar = confSearchFactory.make(rcs);
			pfunc.init(astar, rcs.getNumConformations(), settings.epsilon);
			pfunc.setStabilityThreshold(stabilityThreshold);

			// compute it
			pfunc.compute();

			// save the result
			result = pfunc.makeResult();
			pfuncResults.put(sequence, result);

			/* HACKHACK: we're done using the A* tree, pfunc, etc
				and normally the garbage collector will clean them up,
				along with their off-heap resources (e.g. TPIE data structures).
				Except the garbage collector might not do it right away.
				If we try to allocate more off-heap resources before these get cleaned up,
				we might run out. So poke the garbage collector now and try to get
				it to clean up the off-heap resources right away.
			*/
			Runtime.getRuntime().gc();

			return result;
		}

		public File getConfDBFile() {
			if (settings.confDBPattern == null) {
				return null;
			} else {
				return new File(settings.applyConfDBPattern(type.name().toLowerCase()));
			}
		}

		public void useConfDBIfNeeded(ConfDB.User user) {
			ConfDB.useIfNeeded(confSpace, getConfDBFile(), user);
		}
	}

	private static interface Scorer {
		KStarScore score(int sequenceNumber, PartitionFunction.Result proteinResult, PartitionFunction.Result ligandResult, PartitionFunction.Result complexResult);
	}

	/** A configuration space containing just the protein strand */
	public final ConfSpaceInfo protein;

	/** A configuration space containing just the ligand strand */
	public final ConfSpaceInfo ligand;

	/** A configuration space containing both the protein and ligand strands */
	public final ConfSpaceInfo complex;

	/** Optional and overridable settings for K* */
	public final Settings settings;

	public KStar(SimpleConfSpace protein, SimpleConfSpace ligand, SimpleConfSpace complex, Settings settings) {
		this.settings = settings;
		this.protein = new ConfSpaceInfo(protein, ConfSpaceType.Protein);
		this.ligand = new ConfSpaceInfo(ligand, ConfSpaceType.Ligand);
		this.complex = new ConfSpaceInfo(complex, ConfSpaceType.Complex);
	}

	public Iterable<ConfSpaceInfo> confSpaceInfos() {
		return Arrays.asList(protein, ligand, complex);
	}

	public ConfSpaceInfo getConfSpaceInfo(SimpleConfSpace confSpace) {
		if (confSpace == protein.confSpace) {
			return protein;
		} else if (confSpace == ligand.confSpace) {
			return ligand;
		} else if (confSpace == complex.confSpace) {
			return complex;
		} else {
			throw new IllegalArgumentException("conf space does not match any known by this K* instance");
		}
	}

	public List<ScoredSequence> run() {

		// check the conf space infos to make sure we have all the inputs
		protein.check();
		ligand.check();
		complex.check();

		// reset any previous state
		protein.clear();
		ligand.clear();
		complex.clear();

		List<ScoredSequence> scores = new ArrayList<>();

		// collect the wild type sequences
		protein.sequences.add(protein.confSpace.makeWildTypeSequence());
		ligand.sequences.add(ligand.confSpace.makeWildTypeSequence());
		complex.sequences.add(complex.confSpace.makeWildTypeSequence());

		// collect all the sequences explicitly
		List<List<SimpleConfSpace.Position>> powersetOfPositions = MathTools.powersetUpTo(complex.confSpace.positions, settings.maxSimultaneousMutations);
		Collections.reverse(powersetOfPositions); // NOTE: reverse to match order of old code
		for (List<SimpleConfSpace.Position> mutablePositions : powersetOfPositions) {

			// collect the mutations (res types except for wild type) for these positions into a simple list list
			List<List<String>> resTypes = new ArrayList<>();
			for (SimpleConfSpace.Position pos : mutablePositions) {
				resTypes.add(pos.resFlex.resTypes.stream()
					.filter((resType) -> !resType.equals(pos.resFlex.wildType))
					.collect(Collectors.toList())
				);
			}

			// enumerate all the combinations of res types
			for (List<String> mutations : MathTools.cartesianProduct(resTypes)) {

				// build the complex sequence
				Sequence complexSequence = complex.confSpace.makeWildTypeSequence();
				for (int i=0; i<mutablePositions.size(); i++) {
					complexSequence.set(mutablePositions.get(i), mutations.get(i));
				}
				complex.sequences.add(complexSequence);

				// split complex sequence into protein/ligand sequences
				protein.sequences.add(complexSequence.filter(protein.confSpace));
				ligand.sequences.add(complexSequence.filter(ligand.confSpace));
			}
		}

		// TODO: sequence filtering? do we need to reject some mutation combinations for some reason?

		// now we know how many sequences there are in total
		int n = complex.sequences.size();

		// make the sequence scorer and reporter
		Scorer scorer = (sequenceNumber, proteinResult, ligandResult, complexResult) -> {

			// compute the K* score
			KStarScore kstarScore = new KStarScore(proteinResult, ligandResult, complexResult);
			Sequence complexSequence = complex.sequences.get(sequenceNumber);
			scores.add(new ScoredSequence(complexSequence, kstarScore));

			// report scores
			settings.scoreWriters.writeScore(new KStarScoreWriter.ScoreInfo(
				sequenceNumber,
				n,
				complexSequence,
				complex.confSpace,
				kstarScore
			));

			return kstarScore;
		};

		System.out.println("computing K* scores for " + complex.sequences.size() + " sequences to epsilon = " + settings.epsilon + " ...");
		settings.scoreWriters.writeHeader();
		// TODO: progress bar?

		// open the conf databases if needed
		protein.useConfDBIfNeeded((proteinConfDB) -> {
			ligand.useConfDBIfNeeded((ligandConfDB) -> {
				complex.useConfDBIfNeeded((complexConfDB) -> {

					// compute wild type partition functions first (always at pos 0)
					KStarScore wildTypeScore = scorer.score(
						0,
						protein.calcPfunc(0, BigDecimal.ZERO, proteinConfDB),
						ligand.calcPfunc(0, BigDecimal.ZERO, ligandConfDB),
						complex.calcPfunc(0, BigDecimal.ZERO, complexConfDB)
					);
					BigDecimal proteinStabilityThreshold = null;
					BigDecimal ligandStabilityThreshold = null;
					if (settings.stabilityThreshold != null) {
						BigDecimal stabilityThresholdFactor = new BoltzmannCalculator(PartitionFunction.decimalPrecision).calc(settings.stabilityThreshold);
						proteinStabilityThreshold = wildTypeScore.protein.values.calcLowerBound().multiply(stabilityThresholdFactor);
						ligandStabilityThreshold = wildTypeScore.ligand.values.calcLowerBound().multiply(stabilityThresholdFactor);
					}

					// compute all the partition functions and K* scores for the rest of the sequences
					for (int i=1; i<n; i++) {

						// get the pfuncs, with short circuits as needed
						final PartitionFunction.Result proteinResult = protein.calcPfunc(i, proteinStabilityThreshold, proteinConfDB);
						final PartitionFunction.Result ligandResult;
						final PartitionFunction.Result complexResult;
						if (!KStarScore.isLigandComplexUseful(proteinResult)) {
							ligandResult = PartitionFunction.Result.makeAborted();
							complexResult = PartitionFunction.Result.makeAborted();
						} else {
							ligandResult = ligand.calcPfunc(i, ligandStabilityThreshold, ligandConfDB);
							if (!KStarScore.isComplexUseful(proteinResult, ligandResult)) {
								complexResult = PartitionFunction.Result.makeAborted();
							} else {
								complexResult = complex.calcPfunc(i, BigDecimal.ZERO, complexConfDB);
							}
						}

						scorer.score(i, proteinResult, ligandResult, complexResult);
					}

				});
			});
		});

		return scores;
	}
}
