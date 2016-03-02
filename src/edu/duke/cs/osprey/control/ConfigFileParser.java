/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.duke.cs.osprey.control;

import edu.duke.cs.osprey.confspace.SearchProblem;
import edu.duke.cs.osprey.dof.deeper.DEEPerSettings;
import edu.duke.cs.osprey.dof.deeper.RamachandranChecker;
import edu.duke.cs.osprey.ematrix.epic.EPICSettings;
import edu.duke.cs.osprey.energy.EnergyFunctionGenerator;
import edu.duke.cs.osprey.restypes.GenericResidueTemplateLibrary;
import edu.duke.cs.osprey.structure.Molecule;
import edu.duke.cs.osprey.structure.PDBFileReader;
import edu.duke.cs.osprey.structure.Residue;
import edu.duke.cs.osprey.energy.forcefield.ForcefieldParams;
import edu.duke.cs.osprey.kstar.AllowedSeqs;
import edu.duke.cs.osprey.kstar.Strand;
import edu.duke.cs.osprey.pruning.PruningControl;
import edu.duke.cs.osprey.pruning.PruningMatrix;
import edu.duke.cs.osprey.tools.ObjectIO;
import edu.duke.cs.osprey.tools.StringParsing;

import java.io.File;
import java.util.ArrayList;
import java.util.StringTokenizer;

/**
 *
 * @author Mark Hallen (mhall44@duke.edu)
 * @author Goke Ojewole (ao68@duke.edu)
 */
public class ConfigFileParser {
    //An object that parses configuration files and uses them to initialize objects needed
    //in various calculations (i.e. for data file loading, conf space definition, pruning, etc.)
    
    ParamSet params = new ParamSet();
    
    public ConfigFileParser(String[] args){
        //parse all config files into params
        
        //check format of args
        if(!args[0].equalsIgnoreCase("-c"))
            throw new RuntimeException("ERROR: bad arguments (should start with -c)");
        
        params.addParamsFromFile(args[1]);//KStar.cfg file
        EnvironmentVars.setDataDir(params.getValue("DataDir"));
        
        for(int argNum=3; argNum<args.length; argNum++)//System.cfg, etc.
            params.addParamsFromFile(args[argNum]);
        
        params.addDefaultParams();//We'll look for this in DataDir
    }
    
	
	AllowedSeqs getAllowedSequences(int strand, AllowedSeqs complexSeqs) {
		
		if( complexSeqs == null ) {
			// this is only true when we have not calculated the sequences for the complex

			ArrayList<String> flexRes = getFlexRes();
			ArrayList<ArrayList<String>> allowedAAs = getAllowedAAs();

			if(flexRes.size() != allowedAAs.size()){
				throw new RuntimeException("ERROR: Number of flexible positions different in flexible residue "
						+ "and allowed AA type parameters!");
			}

			int numMutations = params.getInt("NUMMUTATIONS", 1);
			
			complexSeqs = new AllowedSeqs(strand, setupDEEPer(), 
					freeBBZoneTermini(), moveableStrandTermini(), 
					flexRes, allowedAAs, getWTSequence(), numMutations);

			if(complexSeqs.getNumSeqs() == 0)
				throw new RuntimeException("ERROR: cannot generate any sequences "
						+ "for NUMMUTATIONS=" + numMutations + " mutation(s). "
						+ "Change the value of NUMMUTATIONS parameter.");
		}
		
		if(strand == Strand.COMPLEX)
			return complexSeqs;

		// get index values of strand limits in flexRes
		@SuppressWarnings("unchecked")
		ArrayList<String> flexRes = (ArrayList<String>)ObjectIO.deepCopy(complexSeqs.getFlexRes());
		@SuppressWarnings("unchecked")
		ArrayList<ArrayList<String>> allowedAAs = (ArrayList<ArrayList<String>>)ObjectIO.deepCopy(complexSeqs.getAllowedAAs());
		int lb = -1, ub = -1;
		Strand limits = getCompressedStrandLimits(strand, complexSeqs.getFlexRes());
		lb = flexRes.indexOf( String.valueOf(limits.getStrandBegin()) );
		ub = flexRes.indexOf( String.valueOf(limits.getStrandEnd()) ) + 1;
		
		// filter allowedSeqs for protein and ligand strands
		filterResiduesByStrand( strand, flexRes, allowedAAs );
		
		ArrayList<String[]> fbbzt = complexSeqs.getFreeBBZoneTermini();
		ArrayList<String[]> mst = complexSeqs.getMoveableStrandTermini();
		
		if( fbbzt.size() == 2 ) fbbzt = new ArrayList<String[]>( complexSeqs.getFreeBBZoneTermini().subList(strand, strand) );
		if( mst.size() == 2 ) mst = new ArrayList<String[]>( complexSeqs.getMoveableStrandTermini().subList(strand, strand) );
		
		AllowedSeqs strandSeqs = new AllowedSeqs(strand, setupDEEPer(strand), 
				fbbzt, mst, flexRes, complexSeqs, allowedAAs, lb, ub);

		return strandSeqs;
	}
	
	
	Strand getCompressedStrandLimits( int strand, ArrayList<String> flexRes ) {

		Strand strandLimits = getStrandLimits(strand);
		ArrayList<String> strandResNums = getFlexResByStrand(strand);

		@SuppressWarnings("unchecked")
		ArrayList<String> flexRes2 = (ArrayList<String>)ObjectIO.deepCopy(flexRes);

		for( int it = 0; it < flexRes2.size(); ) {
			if( !strandLimits.contains( Integer.parseInt(flexRes2.get(it)) ) ) {

				String removed = flexRes2.remove(it);

				if( strandResNums.contains( removed ) )
					throw new RuntimeException("ERROR: in strand" + strand + 
							", flexible residue " + removed + " exceeds strand limits");
			}
			else ++it;
		}

		ArrayList<String> limits = new ArrayList<>();
		limits.add(flexRes2.get(0));
		limits.add(flexRes2.get(flexRes2.size()-1));
		Strand ans = new Strand(strand, flexRes2.size(), limits);

		return ans;
	}
	
	
	/**
	 * this method mutates the flexRes and allowedAAs parameters 
	 * @param strand
	 * @param flexRes
	 * @param allowedAAs
	 *
	 */
	void filterResiduesByStrand( int strand, ArrayList<String> flexRes, 
			ArrayList<ArrayList<String>> allowedAAs ) {

		Strand strandLimits = getStrandLimits(strand);
		ArrayList<String> strandResNums = getFlexResByStrand(strand);

		for( int it = 0; it < flexRes.size(); ) {
			if( !strandLimits.contains( Integer.parseInt(flexRes.get(it)) ) ) {

				String removed = flexRes.remove(it);

				if( strandResNums.contains( removed ) )
					throw new RuntimeException("ERROR: in strand" + strand + 
							", flexible residue " + removed + " exceeds strand limits");

				allowedAAs.remove(it);
			}
			else ++it;
		}

		if(flexRes.size() != allowedAAs.size()){
			throw new RuntimeException("ERROR: Number of flexible positions different in flexible residue "
					+ "and allowed AA type parameters!");
		}
	}
	
	
	ArrayList<String> getFlexResByStrand( int strand ) {

		if( strand != Strand.COMPLEX && strand != Strand.PROTEIN && strand != Strand.LIGAND )
			throw new RuntimeException("ERROR: specified strand " + strand + " is invalid");

		ArrayList<String> flexResList = new ArrayList<>();

		String resListString = params.getValue("strandMut"+strand);
		StringTokenizer tokenizer = new StringTokenizer(resListString);

		while(tokenizer.hasMoreTokens()){
			flexResList.add( tokenizer.nextToken() );
		}

		return flexResList;
	}
	
	
	int getNumStrands() {
		StringTokenizer tokenizer = 
				new StringTokenizer(params.getValue( "STRANDMUTNUMS" ));
		return tokenizer.countTokens();
	}
	
	
	/**
	 * Reads strand limits
	 */
	Strand getStrandLimits( int strand ) {

		if( strand == Strand.COMPLEX ) return null;

		String strandLimits = params.getValue( "STRAND"+strand );

		StringTokenizer tokenizer = 
				new StringTokenizer(params.getValue( "STRANDMUTNUMS" ));
		for( int it = 0; it < strand; ++it ) tokenizer.nextToken(); 
		int numFlexRes = Integer.parseInt( tokenizer.nextToken() );

		tokenizer = new StringTokenizer( strandLimits );

		ArrayList<String> strLimits = new ArrayList<>();
		while( tokenizer.hasMoreTokens() ){
			strLimits.add( tokenizer.nextToken() );
		}

		return new Strand( strand, numFlexRes, strLimits );

	}
	

	/**
	 * Gets the wild type AA identities for flexible positions
	 * @return
	 */
	public ArrayList<String> getWTSequence() {

		Molecule m = PDBFileReader.readPDBFile(params.getValue("PDBNAME"));
		ArrayList<String> flexibleRes = getFlexRes();
		int numPos = flexibleRes.size();
		ArrayList<String> wt = new ArrayList<>(); for( int pos = 0; pos < numPos; ++pos ) wt.add(null);

		for(int pos=0; pos<numPos; pos++) {
			Residue res = m.getResByPDBResNumber( flexibleRes.get(pos) );
			String wtName = res.template.name;
			wt.set(pos, wtName);
		}

		wt.trimToSize();
		return wt;
	}
	
	
	/**
	 * K* version of method
	 * @param strand
	 * @param strandSeqs
	 * @return
	 */
	public SearchProblem getSearchProblem( int strand, AllowedSeqs strandSeqs ) {

		String ematDir = params.getValue("ematdir", "emat.linear");
		ObjectIO.makeDir(ematDir, params.getBool("deleteEmatDir", false));
		String name = ematDir + File.separator + params.getValue("RUNNAME");

		String suffix = Strand.getStrandString(strand);
		
		ArrayList<String[]> moveableStrands = strandSeqs.getMoveableStrandTermini();
        ArrayList<String[]> freeBBZones = strandSeqs.getFreeBBZoneTermini();
        DEEPerSettings dset = strandSeqs.getDEEPerSettings();
        
        System.out.println("CREATING SEARCH PROBLEM.  NAME: "+name);
        
        return new SearchProblem( name+"."+suffix, params.getValue("PDBNAME"), 
        		strandSeqs.getFlexRes(), 
        		strandSeqs.getAllowedAAs(),
                params.getBool("AddWT"), 
                params.getBool("AddWT", true), 
				params.getBool("doMinimize", false),
                new EPICSettings(params),
                params.getBool("UseTupExp", false),
                dset, moveableStrands, freeBBZones,
                params.getBool("useEllipses", false),
                params.getBool("useERef", false),
                params.getBool("AddResEntropy", false) );
	}
	
	
	public PruningControl getPruningControl(SearchProblem searchSpace, double pruningInterval, boolean useEPIC, boolean useTupExp) {
		//setup pruning.  Conformations in searchSpace more than curEw over the GMEC are liable to pruning

		return setupPruning(searchSpace, pruningInterval, useEPIC, useTupExp);
	}
	
    
    DEEPerSettings setupDEEPer() {
        //Set up the DEEPerSettings object, including the PertSet (describes the perturbations)
        String runName = params.getValue("runName");
        
        DEEPerSettings dset = new DEEPerSettings(
                params.getBool("doPerturbations"),
                params.getRunSpecificFileName("perturbationFile", ".pert"),
                params.getBool("selectPerturbations"),
                params.getValue("startingPerturbationFile"),
                params.getBool("onlyStartingPerturbations"),
                params.getDouble("maxShearParam"),
                params.getDouble("maxBackrubParam"),
                params.getBool("selectLCAs"),
                getFlexRes(), 
                params.getValue("PDBNAME")
        );
        
        dset.loadPertFile();//load the PertSet from its file
        return dset;
    }
    
    
    // K* version; can replace the parameter-less version with this version, too
    // but i'm avoiding altering mark's code as much as possible, for now
    DEEPerSettings setupDEEPer(int strand) {
        //Set up the DEEPerSettings object, including the PertSet (describes the perturbations)
        //String runName = params.getValue("runName");
        
        DEEPerSettings dset = new DEEPerSettings(
                params.getBool("doPerturbations"),
                params.getRunSpecificFileName("perturbationFile", ".pert"),
                params.getBool("selectPerturbations"),
                params.getValue("startingPerturbationFile"),
                params.getBool("onlyStartingPerturbations"),
                params.getDouble("maxShearParam"),
                params.getDouble("maxBackrubParam"),
                params.getBool("selectLCAs"),
                getFlexResByStrand(strand),
                params.getValue("PDBNAME")
        );
        
        dset.loadPertFile();//load the PertSet from its file
        return dset;
    }
    
    
    private ArrayList<String[]> freeBBZoneTermini(){
        //Read the termini of the BBFreeBlocks, if any
        ArrayList<String[]> ans = new ArrayList<>();
        
        for(String rt : params.searchParams("BBFREEBLOCK")){
            //So for example BBFREEBLOCK0 120 125 would mean make a BBFreeBlock for res 120-125
            //lexical ordering for blocks is OK
            String strandLimitsString = params.getValue(rt);

            String[] termini = 
                { StringParsing.getToken(strandLimitsString, 1),
                    StringParsing.getToken(strandLimitsString, 2) };

            ans.add(termini);
        }
        
        return ans;
    }
    
    
    private ArrayList<String[]> moveableStrandTermini(){
        //Read the strands that are going to translate and rotate
        //Let's say they can do this regardless of what doMinimize says (that's for sidechains)
        ArrayList<String[]> ans = new ArrayList<>();
        
        for(String rt : params.searchParams("STRANDROTTRANS")){
            if(params.getBool(rt)){
                //So rt = STRANDROTTRANS0 here means strand 0 should translate & rotate
                //OK to go through these params in lexical ordering
                String strandNum = rt.substring(14);
                String strandLimitsString = params.getValue("STRAND"+strandNum);
                
                String[] termini = 
                    { StringParsing.getToken(strandLimitsString, 1),
                        StringParsing.getToken(strandLimitsString, 2) };
                
                ans.add(termini);
            }
        }
        
        return ans;
    }
    
    
    //creation of objects needed in calculations like GMEC and K*
    SearchProblem getSearchProblem(){//this version is for a single search problem...can modify for
        //additional states (unbound, etc.)
        
        String name = params.getValue("RUNNAME");
        
        ArrayList<String> flexRes = getFlexRes();
        ArrayList<ArrayList<String>> allowedAAs = getAllowedAAs();
        
        if(flexRes.size() != allowedAAs.size()){
            throw new RuntimeException("ERROR: Number of flexible positions different in flexible residue "
                    + "and allowed AA type parameters!");
        }
        
        System.out.println("CREATING SEARCH PROBLEM.  NAME: "+name);
        
        ArrayList<String[]> moveableStrands = moveableStrandTermini();
        ArrayList<String[]> freeBBZones = freeBBZoneTermini();
        DEEPerSettings dset = setupDEEPer();
        
        return new SearchProblem( name, params.getValue("PDBNAME"), 
                flexRes, allowedAAs,
                params.getBool("AddWT"), 
                params.getBool("doMinimize"),
                params.getBool("UseEPIC"),
                new EPICSettings(params),
                params.getBool("UseTupExp"),
                dset, moveableStrands, freeBBZones,
                params.getBool("useEllipses"),
                params.getBool("useERef"),
                params.getBool("AddResEntropy") );
    }
    
    
    
    ArrayList<String> getFlexRes(){
        //list of flexible residues.  PDB-based residue numbers
        //we'll include all flexible residues: for compatibility (MAY BE TEMPORARY),
        //all residues in a "StrandMut" record will be included here
        //"StrandMutNums" means something different and thus will be excluded
        ArrayList<String> flexResList = new ArrayList<>();
        
        int numStrands = params.searchParams("STRANDMUT").size() 
                - params.searchParams("STRANDMUTNUMS").size();
        
        //must go through the strands in order to get the right residues 
        for(int smNum=0; smNum<numStrands; smNum++){//must do these in order
            //so we get the right residues
            String param = "STRANDMUT"+smNum;//STRANDMUT0, etc
            
            String resListString = params.getValue(param);
            StringTokenizer tokenizer = new StringTokenizer(resListString);
            
            while(tokenizer.hasMoreTokens()){
                flexResList.add( tokenizer.nextToken() );
            }
        }
        
        return flexResList;
    }
    
    
    ArrayList<ArrayList<String>> getAllowedAAs(){
        //List allowed AA types for each flexible position
        //We can accept either RESALLOWED0_0 (for flexible res 0 of strand 0)
        //or RESALLOWED255 (for residue with PDB number 255)
        //but which one is used should be consistent across all residues
        ArrayList<String> resAllowedRecords = params.searchParams("RESALLOWED");
        
        if(resAllowedRecords.isEmpty())//no flexible residues
            return new ArrayList<ArrayList<String>>();
        else {
            boolean usingStrandFormat = resAllowedRecords.get(0).contains("_");
            for(int rec=1; rec<resAllowedRecords.size(); rec++){
                if( usingStrandFormat != resAllowedRecords.get(rec).contains("_") ){
                    throw new RuntimeException("ERROR: Inconsistent formatting of resAllowed records"
                            + " (should be all by PDB residue number or all by strand)");
                }
            }
            
            if(usingStrandFormat)
                return getAllowedAAsByStrand();
            else
                return getAllowedAAsByPDBResNum();
        }
    }
        
    
    
    ArrayList<ArrayList<String>> getAllowedAAsByStrand(){
        //we'll go through all resAllowed records, ordering first by strand num
        //then pos num in strand
        
        ArrayList<ArrayList<String>> allowedAAs = new ArrayList<>();
        
        //handle better later (now assuming old-style numbering)...
        for(int str=0; true; str++){
            ArrayList<String> resAllowedRecords = params.searchParams("RESALLOWED"+str);
            int numRecordsInStrand = resAllowedRecords.size();
            
            //must go through residues in numerical order
            for(int recNum=0; recNum<numRecordsInStrand; recNum++){
                String param = "RESALLOWED" + str + "_" + recNum;
                    
                String allowedAAString = params.getValue(param);
                
                //parse AA types from allowedAAString
                ArrayList<String> resAllowedAAs = new ArrayList<>();
                StringTokenizer tokenizer = new StringTokenizer(allowedAAString);
            
                while(tokenizer.hasMoreTokens()){
                    resAllowedAAs.add( tokenizer.nextToken() );
                }
                
                allowedAAs.add(resAllowedAAs);
            }
            
            if(numRecordsInStrand==0)//finished with strands that have flexible residues
                break;
        }
        
        return allowedAAs;
    }
    
    
    
    ArrayList<ArrayList<String>> getAllowedAAsByPDBResNum(){
        //we'll go through all resAllowed records, ordering first by strand num
        //then pos num in strand
        
        ArrayList<ArrayList<String>> allowedAAs = new ArrayList<>();
        ArrayList<String> flexRes = getFlexRes();
        
        for(int flexResNum=0; flexResNum<flexRes.size(); flexResNum++){
            String param = "RESALLOWED" + flexRes.get(flexResNum);
            String allowedAAString = params.getValue(param);

            //parse AA types from allowedAAString
            ArrayList<String> resAllowedAAs = new ArrayList<>();
            StringTokenizer tokenizer = new StringTokenizer(allowedAAString);

            while(tokenizer.hasMoreTokens()){
                resAllowedAAs.add( tokenizer.nextToken() );
            }

            allowedAAs.add(resAllowedAAs);
        }
        
        return allowedAAs;
    }
    
    
    PruningControl setupPruning(SearchProblem searchSpace, double pruningInterval, boolean useEPIC, boolean useTupExp){
        //setup pruning.  Conformations in searchSpace more than (Ew+Ival) over the GMEC are liable to pruning
        
        //initialize the pruning matrix for searchSpace, if not already initialized
        //or if pruningInterval lower (so it may have pruned tuples that shouldn't
        //be pruned with our new pruningInterval)
        boolean initPruneMat = false;
        if(searchSpace.pruneMat==null)
            initPruneMat = true;
        else if(searchSpace.pruneMat.getPruningInterval() < pruningInterval)
            initPruneMat = true;
        
        if(initPruneMat)
            searchSpace.pruneMat = new PruningMatrix(searchSpace.confSpace, pruningInterval);
        
        return new PruningControl( searchSpace, pruningInterval, params.getBool("TYPEDEP"), 
            params.getDouble("BOUNDSTHRESH"), params.getInt("ALGOPTION"), 
            params.getBool("USEFLAGS"),
            params.getBool("USETRIPLES"), false, useEPIC, useTupExp,
            params.getDouble("STERICTHRESH") );//FOR NOW NO DACS
    }
    
    
    
    
    
    //loading of data files
    //residue templates, rotamer libraries, forcefield parameters, and Ramachandran data
    public void loadData(){
                
        boolean usePoissonBoltzmann = params.getBool("USEPOISSONBOLTZMANN");
        boolean useEEF1 = params.getBool("DOSOLVATIONE") && (!usePoissonBoltzmann);
        
        //a lot of this depends on forcefield type so figure that out first
        //general forcefield data loaded into the ForcefieldParams in EnvironmentVars
        ForcefieldParams curForcefieldParams = new ForcefieldParams(
                params.getValue("Forcefield"),
                params.getBool("DISTDEPDIELECT"),
		params.getDouble("DIELECTCONST"),
                params.getDouble("VDWMULT"),
		useEEF1,//Only EEF1 solvation is part of the forcefield (P-B calls Delphi)
		params.getDouble("SOLVSCALE"),
                params.getBool("HELECT"),
                params.getBool("HVDW") );
        
        
        EnvironmentVars.curEFcnGenerator = new EnergyFunctionGenerator( 
                curForcefieldParams,
                params.getDouble("SHELLDISTCUTOFF"),
                usePoissonBoltzmann );
        
        String[] resTemplateFiles = getResidueTemplateFiles(curForcefieldParams.forcefld);
        
        GenericResidueTemplateLibrary resTemplates = new GenericResidueTemplateLibrary( resTemplateFiles, curForcefieldParams );
        
        //load template coordinates (necessary for all residues we might want to mutate to)
        //these will be matched to templates
        resTemplates.loadTemplateCoords("all_amino_coords.in");
        
        //load rotamer libraries; the names of residues as they appear in the rotamer library file will be matched to templates
        boolean dunbrackRots = params.getBool("UseDunbrackRotamers");
        // PGC 2015: Always load the Lovell Rotamer Library.
    	resTemplates.loadRotamerLibrary(params.getValue("ROTFILE"), false);//see below; also gRotFile0 etc
    	
    	// AAO load generic rotamer libraries
    	for(String grotFile : params.searchParams("GROTFILE")) {
    		resTemplates.loadRotamerLibrary(params.getValue(grotFile), false);
    	}
    	
    	if(dunbrackRots){ // Use the dunbrack rotamer library
        	resTemplates.loadRotamerLibrary(params.getValue("DUNBRACKROTFILE"), true);//see below; also gRotFile0 etc
        }
        
        resTemplates.loadResEntropy(params.getValue("RESENTROPYFILE"));
        
        //let's make D-amino acid templates by inverting the L-amino acid templates 
        resTemplates.makeDAminoAcidTemplates();
        
        EnvironmentVars.resTemplates = resTemplates;
        
        
        String ramaGlyFile = params.getValue("RAMAGLYFILE");

        if( ! ramaGlyFile.equalsIgnoreCase("none") ){
            String ramaFiles[] = { EnvironmentVars.getDataDir() + ramaGlyFile,
            EnvironmentVars.getDataDir() + params.getValue("RAMAPROFILE"),
            EnvironmentVars.getDataDir() + params.getValue("RAMAGENFILE"),
            EnvironmentVars.getDataDir() + params.getValue("RAMAPREPROFILE")
            };
            RamachandranChecker.getInstance().readInputFiles( ramaFiles );
        }
        
        
        /*
         * A lot of this is similar to this KSParser.setConfigPars code:
         * 
         hElect = (new Boolean((String)rParams.getValue("HELECT", "true"))).booleanValue();
		hVDW = (new Boolean((String)rParams.getValue("HVDW", "true"))).booleanValue();
		hSteric = (new Boolean((String)rParams.getValue("HSTERIC","false"))).booleanValue();
		distDepDielect = (new Boolean((String)rParams.getValue("DISTDEPDIELECT","true"))).booleanValue();
		dielectConst = (new Double((String)rParams.getValue("DIELECTCONST","6.0"))).doubleValue();
		doDihedE = (new Boolean((String)rParams.getValue("DODIHEDE","false"))).booleanValue();
		doSolvationE = (new Boolean((String)rParams.getValue("DOSOLVATIONE","true"))).booleanValue();
		solvScale = (new Double((String)rParams.getValue("SOLVSCALE","0.5"))).doubleValue();
		softvdwMultiplier = (new Double((String)rParams.getValue("VDWMULT","0.95"))).doubleValue();
		stericThresh = (new Double((String)rParams.getValue("STERICTHRESH","0.4"))).doubleValue();
		softStericThresh = (new Double((String)rParams.getValue("SOFTSTERICTHRESH","1.5"))).doubleValue();
		EnvironmentVars.setDataDir(rParams.getValue("DATADIR","./"));
		EnvironmentVars.setForcefld(rParams.getValue("FORCEFIELD","AMBER"));
		double entropyScale = (new Double((String)rParams.getValue("ENTROPYSCALE","0.0"))).doubleValue();
		EnvironmentVars.setEntropyScale(entropyScale);
		
		EnvironmentVars.setAArotLib(EnvironmentVars.getDataDir().concat(rParams.getValue("ROTFILE","LovellRotamer.dat")));

                EnvironmentVars.autoFix = new Boolean((String)rParams.getValue("AUTOFIX","true")).booleanValue();
                
                String ramaGlyFile = (String)rParams.getValue("RAMAGLYFILE","rama500-gly-sym.data");

                if( ! ramaGlyFile.equalsIgnoreCase("none") ){
                    String ramaFiles[] = { EnvironmentVars.dataDir + ramaGlyFile,
                    EnvironmentVars.dataDir + (String)rParams.getValue("RAMAPROFILE","rama500-pro.data"),
                    EnvironmentVars.dataDir + (String)rParams.getValue("RAMAGENFILE","rama500-general.data"),
                    EnvironmentVars.dataDir + (String)rParams.getValue("RAMAPREPROFILE","rama500-prepro.data")
                    };
                    RamachandranChecker.getInstance().readInputFiles( ramaFiles );
                }

         */
    }
    
    
    String[] getResidueTemplateFiles (ForcefieldParams.FORCEFIELD forcefld){
        //return names of residue template files
        
        //template file names are currently fixed
        String aaFilename=null, aaNTFilename=null, aaCTFilename=null, grFilename=null;
        
        switch(forcefld){
                case AMBER:
                        //KER: This is for the standard amber parameters
                        aaFilename =  "all_amino94.in";
                        aaNTFilename =  "all_aminont94.in";
                        aaCTFilename =  "all_aminoct94.in";
                        grFilename = "all_nuc94_and_gr.in";
                        break;
                case CHARMM22:
                        //KER: This if for using the charmm22 parameters:
                        aaFilename = "all_amino_charmm22.txt";
                        aaNTFilename = "all_amino_charmm22_nt.txt";
                        aaCTFilename = "all_amino_charmm22_ct.txt";
                        grFilename = "all_nuc_and_gr_charmm.in";
                        break;
                case CHARMM19NEUTRAL:
                        //KER: This is for CHARMM19 parameters:
                        aaFilename =  "all_amino_charmm19_neutral.in";
                        aaNTFilename =  "all_amino_charmm19_neutral_nt.in";
                        aaCTFilename =  "all_amino_charmm19_neutral_ct.in";
                        grFilename = "all_nuc_and_gr_charmm.in";
                        break;
                case CHARMM19:
                        aaFilename =  "all_amino_charmm19.in";
                        aaNTFilename =  "all_amino_charmm19_nt.in";
                        aaCTFilename =  "all_amino_charmm19_ct.in";
                        grFilename = "all_nuc_and_gr_charmm.in";
                        break;
                default:
                        System.out.println("FORCEFIELD not recognized...Exiting");
                        System.exit(0);
        }
        
        return new String[] {
            aaFilename, aaNTFilename, aaCTFilename, grFilename
        };
    }
    
    
    // Getter function for the params.
    public ParamSet getParams(){
    	return this.params;
    }
    
    
}
