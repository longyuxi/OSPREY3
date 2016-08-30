
#ifdef cl_khr_fp64
    #pragma OPENCL EXTENSION cl_khr_fp64 : enable
#elif defined(cl_amd_fp64)
    #pragma OPENCL EXTENSION cl_amd_fp64 : enable
#else
    #error "Double precision floating point not supported by OpenCL implementation."
#endif


int getAtomIndex(int flags) {
	return abs(flags) - 1;
}

bool isHydrogen(int flags) {
	return flags > 0;
}

bool useDistDepDielec(const int flags) {
	return (flags & 0x1) == 1;
}

bool useHEs(const int flags) {
	return ((flags >> 1) & 0x1) == 1;
}

bool useHVdw(const int flags) {
	return ((flags >> 2) & 0x1) == 1;
}

kernel void calc(
	global const double *coords, global const int *atomFlags, global const double *precomputed, global const int *subsetTable, global double *out,
	const int numPairs, const int num14Pairs, const double coulombFactor, const double scaledCoulombFactor,
	const double solvCutoff2, const int flags,
	local double *scratch
) {

	// NOTE: looks like we're running severely short on gpu registers
	// if the compiler says we used 29 registers, everything seems to work fine
	// if we use too many registers though, we get CL_OUT_OF_RESOURCES errors
	// not sure how many we're allowed to use though
	// so try to contain intermediate calculations in the smallest possible scopes
	// that's why the code looks all weird
	// it's optimized to minimize register usage at the expense of readability
	// we also re-compute values sometimes to save registers
	
	// NOTE: CL_OUT_OF_RESOURCES can also be thrown when you misuse a pointer (ie like a segfault)

	// NOTE: can't have bools in kernel args for some reason, so the int flags encode 0=false, 1=true
	// pos 0 is useDistDepDielec
	// pos 1 is useHEs
	// pos 2 is useHVdw
	// packing the bools into one int saves registers too
	
	// start with zero energy
	double energy = 0;
	
	// which atom pair are we calculating?
	if (get_global_id(0) < numPairs) {
		int i = subsetTable[get_global_id(0)];
	
		// read atom flags and calculate all the things that use the atom flags in this scope
		bool bothHeavy;
		double r2 = 0;
		{
			int atom1Flags, atom2Flags;
			{
				int i2 = i*2;
				atom1Flags = atomFlags[i2];
				atom2Flags = atomFlags[i2 + 1];
			}
			
			bothHeavy = !isHydrogen(atom1Flags) && !isHydrogen(atom2Flags);
			
			// calculate the squared radius
			int atom1Index3 = getAtomIndex(atom1Flags)*3;
			int atom2Index3 = getAtomIndex(atom2Flags)*3;
			double d;
			d = coords[atom1Index3] - coords[atom2Index3];
			r2 += d*d;
			d = coords[atom1Index3 + 1] - coords[atom2Index3 + 1];
			r2 += d*d;
			d = coords[atom1Index3 + 2] - coords[atom2Index3 + 2];
			r2 += d*d;
		}
		
		int i9 = i*9;
		
		// calculate electrostatics
		if (bothHeavy || useHEs(flags)) {
		
			double esEnergy = 1;
			
			{
				bool is14Pair = get_global_id(0) < num14Pairs;
				esEnergy *= is14Pair ? scaledCoulombFactor : coulombFactor;
			}
			
			{
				double charge = precomputed[i9 + 2];
				esEnergy *= charge;
			}
			
			{
				esEnergy /= useDistDepDielec(flags) ? r2 : sqrt(r2);
			}
			
			energy += esEnergy;
		}
		
		// calculate vdw
		if (bothHeavy || useHVdw(flags)) {
			
			double Aij, Bij;
			{
				Aij = precomputed[i9];
				Bij = precomputed[i9 + 1];
			}
			
			// compute vdw
			double r6 = r2*r2*r2;
			double r12 = r6*r6;
			energy += Aij/r12 - Bij/r6;
		}
		
		// calculate solvation
		if (bothHeavy && r2 < solvCutoff2) {
				
			double r = sqrt(r2);
			{
				double lambda1 = precomputed[i9 + 3];
				double radius1 = precomputed[i9 + 4];
				double alpha1 = precomputed[i9 + 5];
				double Xij = (r - radius1)/lambda1;
				energy -= alpha1*exp(-Xij*Xij)/r2;
			}
			{
				double lambda2 = precomputed[i9 + 6];
				double radius2 = precomputed[i9 + 7];
				double alpha2 = precomputed[i9 + 8];
				double Xji = (r - radius2)/lambda2;
				energy -= alpha2*exp(-Xji*Xji)/r2;
			}
		}
	}
	
	// compute the energy sum in SIMD-style
	// see url for a tutorial on GPU reductions:
	// http://developer.amd.com/resources/articles-whitepapers/opencl-optimization-case-study-simple-reductions/

	int locali = get_local_id(0);
	scratch[locali] = energy;
	
	barrier(CLK_LOCAL_MEM_FENCE);
	
	int localsize = get_local_size(0);
	for (int offset = 1; offset < localsize; offset <<= 1) {
	
		// sum this level of the reduction tree
		int mask = (offset << 1) - 1;
		if ((locali & mask) == 0) {
			scratch[locali] += scratch[locali + offset];
		}
		
		barrier(CLK_LOCAL_MEM_FENCE);
	}
	
	// finally, if we're the 0 thread, write the summed energy for this work group
	if (locali == 0) {
		out[get_group_id(0)] = scratch[0];
	}
}
