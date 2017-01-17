package edu.duke.cs.osprey.kstar;

import java.io.File;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import edu.duke.cs.osprey.dof.deeper.DEEPerSettings;
import edu.duke.cs.osprey.ematrix.epic.EPICSettings;
import edu.duke.cs.osprey.kstar.pfunc.PFAbstract;
import edu.duke.cs.osprey.kstar.pfunc.PFAbstract.EApproxReached;
import edu.duke.cs.osprey.kstar.pfunc.PFAbstract.RunState;
import edu.duke.cs.osprey.kstar.pfunc.PFFactory;
import edu.duke.cs.osprey.kstar.pruning.APrioriPruningProver;
import edu.duke.cs.osprey.pruning.Pruner;
import edu.duke.cs.osprey.pruning.PruningMatrix;
import edu.duke.cs.osprey.tools.ObjectIO;
import edu.duke.cs.osprey.tupexp.LUTESettings;


/**
 * 
 * @author Adegoke Ojewole (ao68@duke.edu)
 *
 */
public abstract class KSAbstract implements KSInterface {

	protected KSConfigFileParser cfp = null;
	protected ArrayList<String> wtSeq = null;
	protected boolean doWTCalc = true;
	protected KSCalc wtKSCalc = null;
	//protected KSCalc bestCalc = null;
	protected String outputDir = null;
	protected String outFilePath = null;
	protected String ematDir = null;
	protected String checkPointDir = null;
	protected String runName = null;
	protected String checkPointFilePath = null;

	protected HashMap<Integer, KSAllowedSeqs> strand2AllowedSeqs = new HashMap<>(3);
	protected ConcurrentHashMap<String, KSSearchProblem> name2SP = new ConcurrentHashMap<>();
	protected ConcurrentHashMap<String, PFAbstract> name2PF = new ConcurrentHashMap<>();

	protected double EW;
	protected double I0;
	private String pdbName;
	private boolean useEPIC;
	private boolean useTupExp;
	private boolean useEllipses;
	private boolean useERef;
	private boolean addResEntropy;
	private boolean addWT;
	private boolean addWTRots;
        
        private boolean usePoissonBoltzmann;
        double stericThresh;

	protected boolean useVoxelG;

	public static long runTimeout = 0;
	public static boolean doCheckPoint = false;
	protected static long checkpointInterval = 50000;

	//public static double interMutationConst = 0.0;

	private long startTime = 0; // beginning time
	private int numSeqsCompleted = 0;
	private int numSeqsCreated = 0;

	public KSAbstract( KSConfigFileParser cfp ) {

		this.cfp = cfp;

		EW = cfp.getParams().getDouble("Ew",0);
		I0 = cfp.getParams().getDouble("Ival", 5);
		pdbName = cfp.getParams().getFile("PDBNAME").getAbsolutePath();
		useEPIC = cfp.getParams().getBool("UseEPIC");
		useTupExp = cfp.getParams().getBool("UseTupExp");
		useEllipses = cfp.getParams().getBool("useEllipses");
		useERef = cfp.getParams().getBool("useERef");
		addResEntropy = cfp.getParams().getBool("AddResEntropy");
		addWT = cfp.getParams().getBool("addWT", true);
		addWTRots = cfp.getParams().getBool("addWTRots", true);

		if(!addWT && addWTRots)
			throw new RuntimeException("ERROR: addWTRots is true but addWT is false. addWT must be true if addWTRots is true");

                usePoissonBoltzmann = cfp.getParams().getBool("UsePoissonBoltzmann");
                stericThresh = cfp.getParams().getDouble("StericThresh");
                
		useVoxelG = cfp.getParams().getBool("useVoxelG", false);
		if(useVoxelG && !useTupExp)
			throw new RuntimeException("ERROR: K* with continuous entropy requires LUTE");
	}


	public void checkAPPP(){
		//check if using a-priori-provable pruning, and set it up if we are
		if(cfp.getParams().getBool("APrioriProvablePruning", useTupExp&&!usePoissonBoltzmann)) {
			//in the case of LUTE this is needed for provability
			APrioriPruningProver appp = new APrioriPruningProver(this,cfp,strand2AllowedSeqs);
			EW = appp.calcEw();
			I0 = appp.calcI0();
		}
	}


	public int getNumSeqsCompleted(int increment) {
		numSeqsCompleted += increment;
		return numSeqsCompleted;
	}
	
	
	public int getNumSeqsCreated(int increment) {
		numSeqsCreated += increment;
		return numSeqsCreated;
	}


	public void setStartTime(long time) {
		startTime = time;
	}


	public long getStartTime() {
		return startTime;
	}

	public static void setCheckPointInterval( long interval ) {
		if(interval <= 0) interval = 1;
		checkpointInterval = interval;
	}


	public void createEmats(ArrayList<Boolean> contSCFlexVals) {
		// for now, only the pan seqSPs have energy matrices in the file system
		preparePanSeqSPs(contSCFlexVals);
	}


	protected void createOutputDir() {
		if( !new File(getOutputDir()).exists() )
			ObjectIO.makeDir(getOutputDir(), false);
	}


	protected void createCheckPointDir() {
		if( !new File(getCheckPointDir()).exists() )
			ObjectIO.makeDir(getCheckPointDir(), false);
	}


	protected void createEmatDir() {
		ObjectIO.makeDir(getEMATdir(), cfp.getParams().getBool("kStarDeleteEmatDir", false));
	}


	public static String list1D2String(ArrayList<String> seq, String separator) {
		StringBuilder ans = new StringBuilder();
		for( int i = 0; i < seq.size(); ++i ) {
			if(i > 0) ans.append(separator);
			ans.append(seq.get(i));
		}

		return ans.toString();
	}


	public static ArrayList<String> file2List( String path ) {
		ArrayList<String> ans = new ArrayList<String>();

		try {
			if( !new File(path).exists() ) return ans;

			Scanner s = new Scanner(new File(path));
			while (s.hasNextLine()) ans.add(s.nextLine());
			s.close();

		} catch (Exception ex) {
			throw new Error("can't scan file", ex);
		}

		return ans;
	}


	public static ArrayList<ArrayList<String>> list1D2ListOfLists(ArrayList<String> list) {
		ArrayList<ArrayList<String>> ans = new ArrayList<>();
		for( String s : list ) {
			ArrayList<String> newList = new ArrayList<>();
			newList.add(s);
			ans.add(newList);
		}

		return ans;
	}


	public String getSearchProblemName(boolean contSCFlex, int strand) {

		String flexibility = contSCFlex == true ? "min" : "rig";

		return getEMATdir() + 
				File.separator + 
				getRunName() + "." + 
				flexibility + "." + 
				KSTermini.getTerminiString(strand);
	}


	private String getCheckPointName(boolean contSCFlex, int strand) {

		String flexibility = contSCFlex == true ? "min" : "rig";

		return getCheckPointDir() + 
				File.separator + 
				getRunName() + "." + 
				flexibility + "." + 
				KSTermini.getTerminiString(strand);
	}


	public String getSearchProblemName(boolean contSCFlex, int strand, String pfImpl, ArrayList<String> seq) {
		return getSearchProblemName(contSCFlex, strand) + "." + pfImpl + "." + list1D2String(seq, ".");
	}


	public String getCheckPointName(boolean contSCFlex, int strand, String pfImpl, ArrayList<String> seq) {
		return getCheckPointName(contSCFlex, strand) + "." + pfImpl + "." + list1D2String(seq, ".") +".checkpoint";
	}


	protected void printSequences() {

		doWTCalc = !cfp.getParams().getBool("kStarSkipWTCalc");

		if(!doWTCalc) {
			wtKSCalc = null;
			System.out.println("WARNING: skipping K* calculation for wild-type sequence: ");

			ArrayList<Integer> strands = new ArrayList<Integer>(Arrays.asList(KSTermini.LIGAND, 
					KSTermini.PROTEIN, KSTermini.COMPLEX));

			for( int strand : strands ) 
				strand2AllowedSeqs.get(strand).removeStrandSeq(0); // wt is seq 0
		}

		System.out.println("\nPreparing to compute K* for the following sequences:");
		int i = 0;
		for(ArrayList<String> al : strand2AllowedSeqs.get(KSTermini.COMPLEX).getStrandSeqList()) {
			System.out.println(i++ + "\t" + list1D2String(al, " "));
		}
		System.out.println();
	}
	
	public ArrayList<ArrayList<String>> getSequences(int strand) {
		return strand2AllowedSeqs.get(strand).getStrandSeqList();
	}
	
	public ArrayList<ArrayList<String>> getUniqueSequences(int strand) {
		
		// remove duplicate sequences (but don't change order)
		Set<ArrayList<String>> uniques = new LinkedHashSet<>();
		uniques.addAll(getSequences(strand));
		return new ArrayList<ArrayList<String>>(uniques);
	}
	
	
	protected PFAbstract getPartitionFunction(boolean contSCFlex, int strand, String pfImpl, ArrayList<String> seq) {
		return name2PF.get(getSearchProblemName(contSCFlex, strand, pfImpl, seq));
	}


	protected void loadAndPruneMatrices(HashMap<String,Integer> name2Strand) {

		try {

			System.out.println("\nCreating and pruning pan energy matrices\n");
			long begin = System.currentTimeMillis();

			//name2SP.keySet().parallelStream().forEach(key -> {
			for( String key : name2SP.keySet() ) {

				KSSearchProblem sp = name2SP.get(key);

				// single seq matrices created using the fast construction 
				// method are already pruned according to the pruning window
				if(sp.getEnergyMatrix() == null) {
					sp.loadEnergyMatrix();
                                        if(!usePoissonBoltzmann)
                                            cfp.setupPruning(sp, EW+I0, false, false).prune();
                                        
                                        if(sp.useTupExpForSearch && sp.contSCFlex){
                                            int strand = name2Strand.get(key);
                                            setupLUTESearchProblem(key, strand);
                                        }
                                        else
                                            sp.inverseMat = sp.getInvertedFromUnreducedPruningMatrix(sp);
				}
			}
			//});

			System.out.println("\nFinished creating and pruning energy matrices");
			System.out.println("Running time: " + (System.currentTimeMillis()-begin)/1000 + " seconds\n");
		} 

		catch (Exception ex) {
			throw new Error("can't load prune matrices", ex);
		} 
	}
        
        
        private void setupLUTESearchProblem(String key, int strand){
            //Use LUTE fitting to replace a continuous search problem in name2SP (denoted there by key)
            //with a rigid search problem that gives the same energies (within fitting error)
            //for unpruned conformations
            KSSearchProblem contSP = name2SP.get(key);//contSP already has its energy & pruning matrices
            
            if(usePoissonBoltzmann){
                contSP.pruneMat = new PruningMatrix(contSP.confSpace,Double.POSITIVE_INFINITY);//not iMinDEE

                //We can only do steric pruning
                //May want to set a lower thresh than the default (30 perhaps)
                Pruner pruner = new Pruner(contSP, false, 0, 0, false, false);
                pruner.pruneSteric(stericThresh);
                
                contSP.loadTupExpEMatrix();
            }
            else {
                if(contSP.useEPIC){//currently only supporting EPIC in K* runs if LUTE also used...
                    contSP.loadEPICMatrix();
                    if(contSP.epicSettings.useEPICPruning){
                        System.out.println("Beginning post-EPIC pruning.");
                        cfp.setupPruning(contSP, EW+I0, true, false).prune();
                        System.out.println("Finished post-EPIC pruning.");
                    }
                }

                contSP.loadTupExpEMatrix();
                System.out.println("Beginning post-tup-exp pruning.");
                cfp.setupPruning(contSP, EW, false, true).prune();
                System.out.println("Finished post-tup-exp pruning.");
            }
            
            //Now the pruning matrix and LUTE matrix in contSP define the rigid search problem we want
            //create this search problem explicitly
            KSSearchProblem luteSP = createPanSeqSP(false,strand);
            luteSP.pruneMat = contSP.pruneMat;
            luteSP.emat = contSP.tupExpEMat;
            
            //finally, set up inverse matrix and replace contSP with luteSP in name2SP
            luteSP.inverseMat = luteSP.getInvertedFromUnreducedPruningMatrix(luteSP);
            name2SP.put(key,luteSP);
        }


	protected PFAbstract createPF4Seq(boolean contSCFlex, int strand, ArrayList<String> seq, String pfImpl) {
		PFAbstract ans = null;

		String panSeqSPName = getSearchProblemName(contSCFlex, strand);
		KSSearchProblem panSeqSP = name2SP.get(panSeqSPName);

		String seqSPName = getSearchProblemName(contSCFlex, strand, pfImpl, seq);
		String cpName = getCheckPointName(contSCFlex, strand, pfImpl, seq);

		if( (ans = name2PF.get(seqSPName)) != null ) return ans;
		if( doCheckPoint && (ans = deSerializePF(seqSPName, cpName, contSCFlex)) != null ) return ans;

		ArrayList<Integer> flexResIndexes = strand2AllowedSeqs.get(strand).getFlexResIndexesFromSeq(seq);

		// create partition function
		ans = PFFactory.getPartitionFunction(pfImpl, strand, seq, flexResIndexes, cpName, seqSPName, cfp, panSeqSP);

		return ans;
	}


	protected ConcurrentHashMap<Integer, PFAbstract> createPFs4Seqs(ArrayList<ArrayList<String>> seqs, 
			ArrayList<Boolean> contSCFlexVals, ArrayList<String> pfImplVals) {

		ConcurrentHashMap<Integer, PFAbstract> ans = new ConcurrentHashMap<>();

		ArrayList<Integer> strands = new ArrayList<Integer>(Arrays.asList(KSTermini.COMPLEX, KSTermini.PROTEIN, KSTermini.LIGAND));
		ArrayList<Integer> indexes = new ArrayList<>();
		for(int i = 0; i < strands.size(); i++) indexes.add(i);

		//indexes.parallelStream().forEach((index) -> {
		for(int index = 0; index < strands.size(); ++index) {

			int strand = strands.get(index);
			boolean contSCFlex = contSCFlexVals.get(strand);
			String pfImpl = pfImplVals.get(strand);
			ArrayList<String> seq = seqs.get(strand);

			PFAbstract pf = createPF4Seq(contSCFlex, strand, seq, pfImpl);

			// put partition function in global map
			name2PF.put(pf.getReducedSearchProblemName(), pf);

			// put in local map
			ans.put(strand, pf);

			// only continue if we have not already started computed the PF
			if( pf.getRunState() == RunState.NOTSTARTED ) {

				// get energy matrix
				if(pf.getReducedSearchProblem().getEnergyMatrix() == null) {
					pf.getReducedSearchProblem().loadEnergyMatrix();
				}

				// re-prune, since we have fewer witnesses now that we have trimmed the emat?
				// type dependent pruning doesn't take care of this?

				if(pf.getReducedSearchProblem().numConfs(pf.getReducedPruningMatrix()).compareTo(BigInteger.ZERO) == 0) {
					// no conformations in search space, so this cannot give a valid
					// partition function
					pf.setEpsilonStatus(EApproxReached.NOT_POSSIBLE);

					System.out.println("\nWARNING: there are no valid conformations for sequence " + 
							KSAbstract.list1D2String(pf.getSequence(), " ") + " " + pf.getFlexibility() + "\n");
				}

				else {	
					// initialize conf counts for K*
					pf.setNumUnPruned();
					pf.setNumPruned();
				}
			}
		}
		//});

		if(ans.size() != 3)
			throw new RuntimeException("ERROR: returned map must contain three different partition functions");

		return ans;
	}


	public void removeFromMap(String spName, boolean sp, boolean pf) {
		if(sp) name2SP.remove(spName);
		if(pf) name2PF.remove(spName);
	}


	public KSSearchProblem createPanSeqSP( boolean contSCFlex, int strand ) {

		ArrayList<ArrayList<String>> allowedAAs = KSAllowedSeqs.removePosFromAllowedAAs(strand2AllowedSeqs.get(strand).getAllowedAAs());
		ArrayList<String> flexibleRes = strand2AllowedSeqs.get(strand).getFlexRes();
		ArrayList<String[]> moveableStrands = strand2AllowedSeqs.get(strand).getMoveableStrandTermini();
		ArrayList<String[]> freeBBZones = strand2AllowedSeqs.get(strand).getFreeBBZoneTermini();
		DEEPerSettings dset = strand2AllowedSeqs.get(strand).getDEEPerSettings();

		// create searchproblem
		KSSearchProblem panSeqSP;
                if(contSCFlex){
                    panSeqSP = new KSSearchProblem( 
                                    getSearchProblemName(contSCFlex, strand), 
                                    pdbName, 
                                    flexibleRes, 
                                    allowedAAs, 
                                    addWT, 
                                    contSCFlex,
                                    useEPIC,
                                    new EPICSettings(cfp.getParams()),
                                    useTupExp,
                                    new LUTESettings(cfp.getParams()),
                                    dset, 
                                    moveableStrands, 
                                    freeBBZones,
                                    useEllipses,
                                    useERef,
                                    addResEntropy,
                                    addWTRots,
                                    cfp.getStrandLimits(strand),
                                    useVoxelG);
                }
                else {
                    panSeqSP = new KSSearchProblem( 
                                    getSearchProblemName(contSCFlex, strand), 
                                    pdbName, 
                                    flexibleRes, 
                                    allowedAAs, 
                                    addWT, 
                                    contSCFlex,
                            
                                    //No EPIC and LUTE needed for discrete case
                                    false,
                                    new EPICSettings(),
                                    false,
                                    new LUTESettings(),
                            
                                    //Need to strip out non-sidechain continuous flexibility
                                    dset.makeDiscreteVersion(), 
                                    new ArrayList<>(), 
                                    new ArrayList<>(),
                            
                                    useEllipses,
                                    useERef,
                                    addResEntropy,
                                    addWTRots,
                                    cfp.getStrandLimits(strand),
                            
                                    false);
                }

		return panSeqSP;
	}


	protected void preparePanSeqSPs( ArrayList<Boolean> contSCFlexVals ) {

		ArrayList<Integer> strands = new ArrayList<Integer>(Arrays.asList(KSTermini.LIGAND, 
				KSTermini.PROTEIN, KSTermini.COMPLEX));

                HashMap<String,Integer> name2Strand = new HashMap<>();
                
		for( boolean contSCFlex : contSCFlexVals ) {

			//strands.parallelStream().forEach(strand -> {
			for( int strand : strands ) {

				String spName = getSearchProblemName(contSCFlex, strand);
                                name2Strand.put(spName, strand);

				if( !name2SP.containsKey(spName) ) {
					KSSearchProblem sp = createPanSeqSP(contSCFlex, strand);
					name2SP.put(sp.name, sp);
				}
			}
			//});	
		}

		loadAndPruneMatrices(name2Strand);
	}


	protected String getOputputFilePath() {

		if(outFilePath == null)
			outFilePath = getOutputDir() + File.separator + getRunName() + "." + getSummaryFileExtension();

		return outFilePath;
	}


	protected String getCheckPointFilePath() {

		if(checkPointFilePath == null)
			checkPointFilePath = getCheckPointDir() + File.separator + getOputputFilePath();

		return checkPointFilePath;
	}


	protected String getSummaryFileExtension() {

		String ans = null;
		try {

			ans = InetAddress.getLocalHost().getHostName()
					+ "." + getKSMethod()
					+ "." + PFAbstract.getCFGImpl()
					+ ".txt";

		} catch (Exception ex) {
			throw new Error("can't get hostname", ex);
		}

		return ans;
	}


	protected String getOutputDir() {
		if(outputDir == null) {
			outputDir = cfp.getParams().getValue("kStarOutputDir", "runName");
			if(outputDir.equalsIgnoreCase("runName")) outputDir = getRunName();
		}
		return outputDir; 
	}


	protected String getRunName() {
		if(runName == null) runName = cfp.getParams().getValue("runName");
		return runName;
	}


	protected String getEMATdir() {
		if(ematDir == null) ematDir = getOutputDir() + File.separator + cfp.getParams().getValue("kStarEmatDir");
		return ematDir;
	}


	protected synchronized String getCheckPointDir() {
		if(checkPointDir == null) checkPointDir = getOutputDir() + File.separator + cfp.getParams().getValue("kStarCheckPointDir");
		return checkPointDir;
	}


	protected ArrayList<String> getWTSeq() {
		if( wtSeq == null ) wtSeq = strand2AllowedSeqs.get(KSTermini.COMPLEX).getWTSeq();
		return wtSeq;
	}


	protected KSCalc getWTKSCalc() {
		return wtKSCalc;
	}


	protected boolean isWT( KSCalc mutSeq ) {
		return mutSeq.getPF(KSTermini.COMPLEX).getSequence().equals(getWTSeq());
	}


	protected BigInteger countMinimizedConfs() {
		BigInteger ans = BigInteger.ZERO;
		for(PFAbstract pf : name2PF.values()) ans = ans.add(pf.getNumMinimized4Output());
		return ans;
	}


	protected BigInteger countTotNumConfs() {
		BigInteger ans = BigInteger.ZERO;
		for(PFAbstract pf : name2PF.values()) ans = ans.add(pf.getNumUnPruned());
		return ans;
	}


	protected void abortPFs() {
		name2PF.keySet().parallelStream().forEach(key -> {
			PFAbstract pf = name2PF.get(key);
			pf.abort(true);
		});
	}


	protected ArrayList<ArrayList<String>> getStrandStringsAtPos(int i) {

		ArrayList<ArrayList<String>> ans = new ArrayList<ArrayList<String>>(Arrays.asList(null, null, null));

		ans.set(KSTermini.COMPLEX, strand2AllowedSeqs.get(KSTermini.COMPLEX).getStrandSeqAtPos(i));
		ans.set(KSTermini.PROTEIN, strand2AllowedSeqs.get(KSTermini.PROTEIN).getStrandSeqAtPos(i));
		ans.set(KSTermini.LIGAND, strand2AllowedSeqs.get(KSTermini.LIGAND).getStrandSeqAtPos(i));

		ans.trimToSize();
		return ans;
	}


	protected KSCalc computeWTCalc() {

		if( !doCheckPoint || !new File(getOputputFilePath()).exists() ) KSCalc.printSummaryHeader(getOputputFilePath());
		if( doCheckPoint && !new File(getCheckPointFilePath()).exists() ) KSCalc.printSummaryHeader(getCheckPointFilePath());

		// compute wt sequence for reference
		ArrayList<ArrayList<String>> strandSeqs = getStrandStringsAtPos(0);		
		boolean contSCFlex = cfp.getParams().getBool("doMinimize", true);
		ArrayList<Boolean> contSCFlexVals = new ArrayList<Boolean>(Arrays.asList(contSCFlex, contSCFlex, contSCFlex));
		ArrayList<String> pfImplVals = new ArrayList<String>(Arrays.asList(PFAbstract.getCFGImpl(), 
				PFAbstract.getCFGImpl(), PFAbstract.getCFGImpl()));

		ConcurrentHashMap<Integer, PFAbstract> pfs = createPFs4Seqs(strandSeqs, contSCFlexVals, pfImplVals);
		KSCalc calc = new KSCalc(0, pfs);

		PFAbstract pf = calc.getPF(KSTermini.COMPLEX);
		if(doCheckPoint && getSeqsFromFile(getOputputFilePath()).contains(pf.getSequence())) {
			// we have previously computed the sequence
			return calc;
		}

		calc.run(calc, false, true);

		// protein and ligand must reach epsilon, regardless of checkppoint
		for( int strand : Arrays.asList(KSTermini.LIGAND, KSTermini.PROTEIN, KSTermini.COMPLEX) ) {
			pf = calc.getPF(strand);

			if( (strand != KSTermini.COMPLEX && pf.getEpsilonStatus() != EApproxReached.TRUE) ||
					(strand == KSTermini.COMPLEX && pf.getEpsilonStatus() == EApproxReached.NOT_POSSIBLE) ) {

				throw new RuntimeException("ERROR: could not compute the wild-type sequence "
						+ KSAbstract.list1D2String(pf.getSequence(), " ") + " to an epsilon value of "
						+ PFAbstract.targetEpsilon + ". Resolve any clashes involving the flexible residues, "
						+"or increase the value of epsilon." );
			}
		}

		if( doCheckPoint ) {
			pf = calc.getPF(KSTermini.COMPLEX);
			name2PF.remove(pf.getReducedSearchProblemName());
			calc.deleteSeqFromFile( pf.getSequence(), getCheckPointFilePath() );
			calc.serializePFs();
		}

		if( calc.getEpsilonStatus() == EApproxReached.TRUE ) {
			calc.deleteCheckPointFile(KSTermini.COMPLEX);
			calc.printSummary( getOputputFilePath(), getStartTime(), getNumSeqsCreated(1), getNumSeqsCompleted(1) );
		}

		else {
			calc.printSummary( getCheckPointFilePath(), getStartTime(), getNumSeqsCreated(0), getNumSeqsCompleted(0) );
		}

		return calc;
	}


	protected PFAbstract deSerializePF( String spName, String path, boolean contSCFlex ) {

		if( !new File(path).exists() ) return null;

		PFAbstract ans = (PFAbstract) ObjectIO.readObject(path, true);
		if(ans != null) {
			name2PF.put(spName, ans);
			// set the panseqsp after de-serializing
			KSSearchProblem panSeqSP = name2SP.get(getSearchProblemName(contSCFlex, ans.getStrand()));
			ans.setPanSeqSP(panSeqSP);
		}
		return ans;
	}


	protected ArrayList<ArrayList<String>> getSeqsFromFile(String path) {

		ArrayList<ArrayList<String>> ans = new ArrayList<>();
		ArrayList<String> lines = file2List(path);
		if(lines.size() == 0) return ans;

		for( ArrayList<String> seq : strand2AllowedSeqs.get(KSTermini.COMPLEX).getStrandSeqList() ) {
			String strSeq = KSAbstract.list1D2String(seq, " ");

			for(String line : lines) {
				if(line.contains(strSeq)) {
					ans.add(seq);
					break;
				}
			}
		}

		return ans;
	}

}