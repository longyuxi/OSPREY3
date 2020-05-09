package edu.duke.cs.osprey.energy.compiled;

import cern.colt.matrix.DoubleFactory1D;
import cern.colt.matrix.DoubleMatrix1D;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import edu.duke.cs.osprey.confspace.compiled.AssignedCoords;
import edu.duke.cs.osprey.confspace.compiled.ConfSpace;
import edu.duke.cs.osprey.confspace.compiled.PosInter;
import edu.duke.cs.osprey.confspace.compiled.motions.DihedralAngle;
import edu.duke.cs.osprey.gpu.BufWriter;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryHandles;
import jdk.incubator.foreign.MemorySegment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static edu.duke.cs.osprey.gpu.Structs.*;


public class CudaConfEnergyCalculator implements ConfEnergyCalculator {

	private static class NativeLib {

		static {
			Native.register("CudaConfEcalc");
		}

		public static native int version_major();
		public static native int version_minor();
		public static native int cuda_version_driver();
		public static native int cuda_version_runtime();
		public static native int cuda_version_required();

		public static native Pointer alloc_conf_space_f32(ByteBuffer conf_space);
		public static native Pointer alloc_conf_space_f64(ByteBuffer conf_space);
		public static native void free_conf_space(Pointer p);

		public static native void assign_f32(Pointer conf_space, ByteBuffer conf, ByteBuffer out);
		public static native void assign_f64(Pointer conf_space, ByteBuffer conf, ByteBuffer out);
		public static native float calc_amber_eef1_f32(Pointer conf_space, ByteBuffer conf, ByteBuffer inters, ByteBuffer out_coords, long num_atoms);
		public static native double calc_amber_eef1_f64(Pointer conf_space, ByteBuffer conf, ByteBuffer inters, ByteBuffer out_coords, long num_atoms);
		public static native float minimize_amber_eef1_f32(Pointer conf_space, ByteBuffer conf, ByteBuffer inters, ByteBuffer out_coords, long num_atoms, ByteBuffer dofsBuf, long numDofs);
		public static native double minimize_amber_eef1_f64(Pointer conf_space, ByteBuffer conf, ByteBuffer inters, ByteBuffer out_coords, long num_atoms, ByteBuffer dofsBuf, long numDofs);
	}

	// biggest LD instruction we can use, in bytes
	// to make sure structs get aligned so we can minimize LD instructions needed
	private static final long WidestGpuLoad = 16;

	private static long padToGpuAlignment(long pos) {
		return BufWriter.padToAlignment(pos, WidestGpuLoad);
	}

	public static boolean isSupported() {
		return NativeLib.cuda_version_driver() > 0 && NativeLib.cuda_version_runtime() > 0;
	}

	/**
	 * Throws a helpful error message if this energy calculator is not supported.
	 */
	public static void checkSupported() {

		Function<Integer,String> versionString = v -> String.format("%d.%d", v/1000, (v % 1000)/10);

		int vDriver = NativeLib.cuda_version_driver();
		int vRequired = NativeLib.cuda_version_required();
		int vRuntime = NativeLib.cuda_version_runtime();

		if (vDriver <= 0) {
			throw new RuntimeException("No CUDA driver installed");
		}

		switch (vRuntime) {
			case -1: throw new RuntimeException("CUDA driver is insufficient."
				+ " Driver supports CUDA " + versionString.apply(vDriver)
				+ ", but CUDA " + versionString.apply(vRequired) + " is needed.");
			case -2: throw new RuntimeException("No CUDA device. Does this machine have an Nvidia GPU?");
			case Integer.MIN_VALUE: throw new RuntimeException("Unrecognized error: " + vRuntime);
			default: break;
		}
	}

	private interface ForcefieldsImpl {
		double calc(Pointer pConfSpace, ByteBuffer confBuf, ByteBuffer intersBuf, ByteBuffer coordsBuf, long numAtoms);
		double minimize(Pointer pConfSpace, ByteBuffer confBuf, ByteBuffer intersBuf, ByteBuffer coordsBuf, long numAtoms, ByteBuffer dofsBuf, long numDofs);
		long paramsBytes();
		void writeParams(BufWriter buf);
		long staticStaticBytes();
		long staticPosBytes(int posi1, int fragi1);
		long posBytes(int posi1, int fragi1);
		long posPosBytes(int posi1, int fragi1, int posi2, int fragi2);
		void writeStaticStatic(BufWriter buf);
		void writeStaticPos(int posi1, int fragi1, BufWriter buf);
		void writePos(int posi1, int fragi1, BufWriter buf);
		void writePosPos(int posi1, int fragi1, int posi2, int fragi2, BufWriter buf);
	}

	private interface AtomPairWriter {
		int size();
		int atomi1(int i);
		int atomi2(int i);
		double[] params(int i);
	}

	private class AmberEef1 implements ForcefieldsImpl {

		class SParamsAmberEef1 extends Struct {
			Bool distance_dependent_dielectric = bool();
			Pad pad = pad(15);
			void init() {
				init(16, "distance_dependent_dielectric", "pad");
			}
		}
		final SParamsAmberEef1 paramsStruct = new SParamsAmberEef1();

		class SAtomPairs extends Struct {
			final Int32 num_amber = int32();
			final Int32 num_eef1 = int32();
			final Pad pad = pad(8);
			void init() {
				init(16, "num_amber", "num_eef1", "pad");
			}
		}
		final SAtomPairs atomPairsStruct = new SAtomPairs();

		class SAtomPairAmberF32a extends Struct {
			final Int32 atomi1 = int32();
			final Int32 atomi2 = int32();
			void init() {
				init(8, "atomi1", "atomi2");
			}
		}
		final SAtomPairAmberF32a amberF32aStruct = new SAtomPairAmberF32a();

		class SAtomPairAmberF32b extends Struct {
			final Float32 esQ = float32();
			final Float32 vdwA = float32();
			final Float32 vdwB = float32();
			final Pad pad = pad(4);
			void init() {
				init(16, "esQ", "vdwA", "vdwB", "pad");
			}
			void setParams(double[] params) {
				esQ.set((float)params[0]);
				vdwA.set((float)params[1]);
				vdwB.set((float)params[2]);
			}
		}
		final SAtomPairAmberF32b amberF32bStruct = new SAtomPairAmberF32b();

		class SAtomPairAmberF64a extends Struct {
			final Int32 atomi1 = int32();
			final Int32 atomi2 = int32();
			final Float64 esQ = float64();
			void setParams(double[] params) {
				esQ.set(params[0]);
			}
			void init() {
				init(16, "atomi1", "atomi2", "esQ");
			}
		}
		final SAtomPairAmberF64a amberF64aStruct = new SAtomPairAmberF64a();

		class SAtomPairAmberF64b extends Struct {
			final Float64 vdwA = float64();
			final Float64 vdwB = float64();
			void init() {
				init(16, "vdwA", "vdwB");
			}
			void setParams(double[] params) {
				vdwA.set(params[1]);
				vdwB.set(params[2]);
			}
		}
		final SAtomPairAmberF64b amberF64bStruct = new SAtomPairAmberF64b();

		class SAtomPairEef1F32a extends Struct {
			final Int32 atomi1 = int32();
			final Int32 atomi2 = int32();
			void init() {
				init(8, "atomi1", "atomi2");
			}
		}
		final SAtomPairEef1F32a eef1F32aStruct = new SAtomPairEef1F32a();

		class SAtomPairEef1F32b extends Struct {
			final Float32 vdwRadius = float32();
			final Float32 oolambda = float32();
			final Float32 alpha = float32();
			final Pad pad = pad(4);
			void init() {
				init(16, "vdwRadius", "oolambda", "alpha", "pad");
			}
			void setParams1(double[] params) {
				vdwRadius.set((float)params[0]);
				oolambda.set((float)(1.0/params[1]));
				alpha.set((float)params[4]);
			}
			void setParams2(double[] params) {
				vdwRadius.set((float)params[2]);
				oolambda.set((float)(1.0/params[3]));
				alpha.set((float)params[5]);
			}
		}
		final SAtomPairEef1F32b eef1F32bStruct = new SAtomPairEef1F32b();

		class SAtomPairEef1F64a extends Struct {
			final Int32 atomi1 = int32();
			final Int32 atomi2 = int32();
			void init() {
				init(8, "atomi1", "atomi2");
			}
		}
		final SAtomPairEef1F64a eef1F64aStruct = new SAtomPairEef1F64a();

		class SAtomPairEef1F64b extends Struct {
			final Float64 vdwRadius = float64();
			final Float64 oolambda = float64();
			void init() {
				init(16, "vdwRadius", "oolambda");
			}
			void setParams1(double[] params) {
				vdwRadius.set(params[0]);
				oolambda.set(1.0/params[1]);
			}
			void setParams2(double[] params) {
				vdwRadius.set(params[2]);
				oolambda.set(1.0/params[3]);
			}
		}
		final SAtomPairEef1F64b eef1F64bStruct = new SAtomPairEef1F64b();

		class SAtomPairEef1F64c extends Struct {
			final Float64 alpha1 = float64();
			final Float64 alpha2 = float64();
			void init() {
				init(16, "alpha1", "alpha2");
			}
			void setParams(double[] params) {
				alpha1.set(params[4]);
				alpha2.set(params[5]);
			}
		}
		final SAtomPairEef1F64c eef1F64cStruct = new SAtomPairEef1F64c();

		AmberEef1() {

			// once we know the precision, init the structs
			paramsStruct.init();
			atomPairsStruct.init();
			amberF32aStruct.init();
			amberF32bStruct.init();
			amberF64aStruct.init();
			amberF64bStruct.init();
			eef1F32aStruct.init();
			eef1F32bStruct.init();
			eef1F64aStruct.init();
			eef1F64bStruct.init();
			eef1F64cStruct.init();
		}

		@Override
		public double calc(Pointer pConfSpace, ByteBuffer confBuf, ByteBuffer intersBuf, ByteBuffer coordsBuf, long numAtoms) {
			return switch (precision) {
				case Float32 -> NativeLib.calc_amber_eef1_f32(pConfSpace, confBuf, intersBuf, coordsBuf, numAtoms);
				case Float64 -> NativeLib.calc_amber_eef1_f64(pConfSpace, confBuf, intersBuf, coordsBuf, numAtoms);
			};
		}

		@Override
		public double minimize(Pointer pConfSpace, ByteBuffer confBuf, ByteBuffer intersBuf, ByteBuffer coordsBuf, long numAtoms, ByteBuffer dofsBuf, long numDofs) {
			return switch (precision) {
				case Float32 -> NativeLib.minimize_amber_eef1_f32(pConfSpace, confBuf, intersBuf, coordsBuf, numAtoms, dofsBuf, numDofs);
				case Float64 -> NativeLib.minimize_amber_eef1_f64(pConfSpace, confBuf, intersBuf, coordsBuf, numAtoms, dofsBuf, numDofs);
			};
		}

		@Override
		public long paramsBytes() {
			return paramsStruct.bytes();
		}

		@Override
		public void writeParams(BufWriter buf) {

			buf.place(paramsStruct);

			// write the amber params
			AmberEnergyCalculator.Settings amberSettings = ((AmberEnergyCalculator)confSpace.ecalcs[0]).settings;
			paramsStruct.distance_dependent_dielectric.set(amberSettings.distanceDependentDielectric);

			// no EEF1 params to write
		}

		private long atomPairsBytes(int numAmber, int numEef1) {
			return atomPairsStruct.bytes()
				+ switch (precision) {
					case Float32 ->
						padToGpuAlignment(numAmber*amberF32aStruct.bytes())
						+ numAmber*amberF32bStruct.bytes()
						+ padToGpuAlignment(numEef1*eef1F32aStruct.bytes())
						+ numEef1*eef1F32bStruct.bytes()
						+ numEef1*eef1F32bStruct.bytes();
					case Float64 ->
						padToGpuAlignment(numAmber*amberF64aStruct.bytes())
						+ numAmber*amberF64bStruct.bytes()
						+ padToGpuAlignment(numEef1*eef1F64aStruct.bytes())
						+ numEef1*eef1F64bStruct.bytes()
						+ numEef1*eef1F64bStruct.bytes()
						+ numEef1*eef1F64cStruct.bytes();
				};
		}

		@Override
		public long staticStaticBytes() {
			return atomPairsBytes(
				confSpace.indicesStatic(0).size(),
				confSpace.indicesStatic(1).size()
			);
		}

		@Override
		public long staticPosBytes(int posi1, int fragi1) {
			return atomPairsBytes(
				confSpace.indicesSinglesByFrag(0, posi1, fragi1).sizeStatics(),
				confSpace.indicesSinglesByFrag(1, posi1, fragi1).sizeStatics()
			);
		}

		@Override
		public long posBytes(int posi1, int fragi1) {
			return atomPairsBytes(
				confSpace.indicesSinglesByFrag(0, posi1, fragi1).sizeInternals(),
				confSpace.indicesSinglesByFrag(1, posi1, fragi1).sizeInternals()
			);
		}

		@Override
		public long posPosBytes(int posi1, int fragi1, int posi2, int fragi2) {
			return atomPairsBytes(
				confSpace.indicesPairsByFrags(0, posi1, fragi1, posi2, fragi2).size(),
				confSpace.indicesPairsByFrags(1, posi1, fragi1, posi2, fragi2).size()
			);
		}

		private void writeAtomPairs(AtomPairWriter amber, AtomPairWriter eef1, BufWriter buf) {

			// make sure we're starting at optimal alignment for GPU loads
			assert (buf.isAligned(WidestGpuLoad)) : "Not aligned!";
			long firstPos = buf.pos;

			buf.place(atomPairsStruct);
			atomPairsStruct.num_amber.set(amber.size());
			atomPairsStruct.num_eef1.set(eef1.size());

			switch (precision) {

				case Float32 -> {

					assert (buf.isAligned(WidestGpuLoad));

					for (int i=0; i<amber.size(); i++) {
						buf.place(amberF32aStruct);
						int atomi1 = amber.atomi1(i);
						int atomi2 = amber.atomi2(i);
						assert (atomi1 != atomi2);
						amberF32aStruct.atomi1.set(atomi1);
						amberF32aStruct.atomi2.set(atomi2);
					}
					buf.skipToAlignment(WidestGpuLoad);

					for (int i=0; i<amber.size(); i++) {
						buf.place(amberF32bStruct);
						amberF32bStruct.setParams(amber.params(i));
					}

					assert (buf.isAligned(WidestGpuLoad));

					for (int i=0; i<eef1.size(); i++) {
						buf.place(eef1F32aStruct);
						int atomi1 = eef1.atomi1(i);
						int atomi2 = eef1.atomi2(i);
						assert (atomi1 != atomi2);
						eef1F32aStruct.atomi1.set(atomi1);
						eef1F32aStruct.atomi2.set(atomi2);
					}
					buf.skipToAlignment(WidestGpuLoad);

					for (int i=0; i<eef1.size(); i++) {
						buf.place(eef1F32bStruct);
						eef1F32bStruct.setParams1(eef1.params(i));
					}

					assert (buf.isAligned(WidestGpuLoad));

					for (int i=0; i<eef1.size(); i++) {
						buf.place(eef1F32bStruct);
						eef1F32bStruct.setParams2(eef1.params(i));
					}

					assert (buf.isAligned(WidestGpuLoad));
				}

				case Float64 -> {

					assert (buf.isAligned(WidestGpuLoad));

					for (int i=0; i<amber.size(); i++) {
						buf.place(amberF64aStruct);
						int atomi1 = amber.atomi1(i);
						int atomi2 = amber.atomi2(i);
						assert (atomi1 != atomi2);
						amberF64aStruct.atomi1.set(atomi1);
						amberF64aStruct.atomi2.set(atomi2);
						amberF64aStruct.setParams(amber.params(i));
					}

					assert (buf.isAligned(WidestGpuLoad));

					for (int i=0; i<amber.size(); i++) {
						buf.place(amberF64bStruct);
						amberF64bStruct.setParams(amber.params(i));
					}

					assert (buf.isAligned(WidestGpuLoad));

					for (int i=0; i<eef1.size(); i++) {
						buf.place(eef1F64aStruct);
						int atomi1 = eef1.atomi1(i);
						int atomi2 = eef1.atomi2(i);
						assert (atomi1 != atomi2);
						eef1F64aStruct.atomi1.set(atomi1);
						eef1F64aStruct.atomi2.set(atomi2);
					}
					buf.skipToAlignment(WidestGpuLoad);

					for (int i=0; i<eef1.size(); i++) {
						buf.place(eef1F64bStruct);
						eef1F64bStruct.setParams1(eef1.params(i));
					}

					assert (buf.isAligned(WidestGpuLoad));

					for (int i=0; i<eef1.size(); i++) {
						buf.place(eef1F64bStruct);
						eef1F64bStruct.setParams2(eef1.params(i));
					}

					assert (buf.isAligned(WidestGpuLoad));

					for (int i=0; i<eef1.size(); i++) {
						buf.place(eef1F64cStruct);
						eef1F64cStruct.setParams(eef1.params(i));
					}

					assert (buf.isAligned(WidestGpuLoad));
				}
			}


			// make sure we ended at optimal alignment for GPU loads
			assert (buf.pos - firstPos == atomPairsBytes(amber.size(), eef1.size()))
				: String.format("overshot by %d bytes", buf.pos - firstPos - atomPairsBytes(amber.size(), eef1.size()));
			assert (buf.isAligned(WidestGpuLoad)) : "Not aligned!";
		}

		@Override
		public void writeStaticStatic(BufWriter buf) {

			ConfSpace.IndicesStatic amberIndices = confSpace.indicesStatic(0);
			ConfSpace.IndicesStatic eef1Indices = confSpace.indicesStatic(1);

			writeAtomPairs(
				new AtomPairWriter() {
					@Override public int size() { return amberIndices.size(); }
					@Override public int atomi1(int i) { return confSpace.getStaticAtomIndex(amberIndices.getStaticAtom1Index(i)); }
					@Override public int atomi2(int i) { return confSpace.getStaticAtomIndex(amberIndices.getStaticAtom2Index(i)); }
					@Override public double[] params(int i) { return confSpace.ffparams(0, amberIndices.getParamsIndex(i)); }
				},
				new AtomPairWriter() {
					@Override public int size() { return eef1Indices.size(); }
					@Override public int atomi1(int i) { return confSpace.getStaticAtomIndex(eef1Indices.getStaticAtom1Index(i)); }
					@Override public int atomi2(int i) { return confSpace.getStaticAtomIndex(eef1Indices.getStaticAtom2Index(i)); }
					@Override public double[] params(int i) { return confSpace.ffparams(1, eef1Indices.getParamsIndex(i)); }
				},
				buf
			);
		}

		@Override
		public void writeStaticPos(int posi1, int fragi1, BufWriter buf) {

			ConfSpace.IndicesSingle amberIndices = confSpace.indicesSinglesByFrag(0, posi1, fragi1);
			ConfSpace.IndicesSingle eef1Indices = confSpace.indicesSinglesByFrag(1, posi1, fragi1);

			writeAtomPairs(
				new AtomPairWriter() {
					@Override public int size() { return amberIndices.sizeStatics(); }
					@Override public int atomi1(int i) { return confSpace.getStaticAtomIndex(amberIndices.getStaticStaticAtomIndex(i)); }
					@Override public int atomi2(int i) { return confSpace.getConfAtomIndex(posi1, amberIndices.getStaticConfAtomIndex(i)); }
					@Override public double[] params(int i) { return confSpace.ffparams(0, amberIndices.getStaticParamsIndex(i)); }
				},
				new AtomPairWriter() {
					@Override public int size() { return eef1Indices.sizeStatics(); }
					@Override public int atomi1(int i) { return confSpace.getStaticAtomIndex(eef1Indices.getStaticStaticAtomIndex(i)); }
					@Override public int atomi2(int i) { return confSpace.getConfAtomIndex(posi1, eef1Indices.getStaticConfAtomIndex(i)); }
					@Override public double[] params(int i) { return confSpace.ffparams(1, eef1Indices.getStaticParamsIndex(i)); }
				},
				buf
			);
		}

		@Override
		public void writePos(int posi1, int fragi1, BufWriter buf) {

			ConfSpace.IndicesSingle amberIndices = confSpace.indicesSinglesByFrag(0, posi1, fragi1);
			ConfSpace.IndicesSingle eef1Indices = confSpace.indicesSinglesByFrag(1, posi1, fragi1);

			writeAtomPairs(
				new AtomPairWriter() {
					@Override public int size() { return amberIndices.sizeInternals(); }
					@Override public int atomi1(int i) { return confSpace.getConfAtomIndex(posi1, amberIndices.getInternalConfAtom1Index(i)); }
					@Override public int atomi2(int i) { return confSpace.getConfAtomIndex(posi1, amberIndices.getInternalConfAtom2Index(i)); }
					@Override public double[] params(int i) { return confSpace.ffparams(0, amberIndices.getInternalParamsIndex(i)); }
				},
				new AtomPairWriter() {
					@Override public int size() { return eef1Indices.sizeInternals(); }
					@Override public int atomi1(int i) { return confSpace.getConfAtomIndex(posi1, eef1Indices.getInternalConfAtom1Index(i)); }
					@Override public int atomi2(int i) { return confSpace.getConfAtomIndex(posi1, eef1Indices.getInternalConfAtom2Index(i)); }
					@Override public double[] params(int i) { return confSpace.ffparams(1, eef1Indices.getInternalParamsIndex(i)); }
				},
				buf
			);
		}

		@Override
		public void writePosPos(int posi1, int fragi1, int posi2, int fragi2, BufWriter buf) {

			ConfSpace.IndicesPair amberIndices = confSpace.indicesPairsByFrags(0, posi1, fragi1, posi2, fragi2);
			ConfSpace.IndicesPair eef1Indices = confSpace.indicesPairsByFrags(1, posi1, fragi1, posi2, fragi2);

			writeAtomPairs(
				new AtomPairWriter() {
					@Override public int size() { return amberIndices.size(); }
					@Override public int atomi1(int i) { return confSpace.getConfAtomIndex(posi1, amberIndices.getConfAtom1Index(i)); }
					@Override public int atomi2(int i) { return confSpace.getConfAtomIndex(posi2, amberIndices.getConfAtom2Index(i)); }
					@Override public double[] params(int i) { return confSpace.ffparams(0, amberIndices.getParamsIndex(i)); }
				},
				new AtomPairWriter() {
					@Override public int size() { return eef1Indices.size(); }
					@Override public int atomi1(int i) { return confSpace.getConfAtomIndex(posi1, eef1Indices.getConfAtom1Index(i)); }
					@Override public int atomi2(int i) { return confSpace.getConfAtomIndex(posi2, eef1Indices.getConfAtom2Index(i)); }
					@Override public double[] params(int i) { return confSpace.ffparams(1, eef1Indices.getParamsIndex(i)); }
				},
				buf
			);
		}
	}

	public final ConfSpace confSpace;
	public final Precision precision;
	public final ForcefieldsImpl forcefieldsImpl;

	private final Pointer pConfSpace;

	// NOTE: prefix the struct classes with S to avoid name collisions with the related Java classes

	class SConfSpace extends Struct {
		Int32 num_pos = int32();
		Int32 max_num_conf_atoms = int32();
		Int32 max_num_dofs = int32();
		Int32 num_molecule_motions = int32();
		Int64 size = int64();
		Int64 positions_offset = int64();
		Int64 static_atoms_offset = int64();
		Int64 params_offset = int64();
		Int64 pos_pairs_offset = int64();
		Int64 molecule_motions_offset = int64();
		Real static_energy;
		Pad pad;
		void init() {
			static_energy = real(precision);
			pad = pad(precision.map(12, 8));
			init(
				precision.map(80, 80),
				"num_pos", "max_num_conf_atoms", "max_num_dofs", "num_molecule_motions",
				"size", "positions_offset", "static_atoms_offset", "params_offset", "pos_pairs_offset",
				"molecule_motions_offset",
				"static_energy", "pad"
			);
		}
	}
	private final SConfSpace confSpaceStruct = new SConfSpace();

	static class SPos extends Struct {
		Int32 num_confs = int32();
		Int32 max_num_atoms = int32();
		Int32 num_frags = int32();
		Pad pad;
		void init() {
			pad = pad(4);
			init(
				16, "num_confs", "max_num_atoms", "num_frags", "pad"
			);
		}
	}
	private final SPos posStruct = new SPos();

	class SConf extends Struct {
		Int64 atoms_offset = int64();
		Int32 frag_index = int32();
		Pad pad1;
		Real internal_energy;
		Int64 num_motions = int64();
		Int64 motions_offset = int64();
		Pad pad2;
		void init() {
			pad1 = pad(precision.map(0, 4));
			internal_energy = real(precision);
			pad2 = pad(precision.map(0, 8));
			init(
				precision.map(32, 48),
				"atoms_offset", "frag_index", "pad1", "internal_energy",
				"num_motions", "motions_offset", "pad2"
			);
		}
	}
	private final SConf confStruct = new SConf();

	static class SArray extends Struct {
		Int64 size = int64();
		Pad pad = pad(8);
		void init() {
			init(
				16, "size", "pad"
			);
		}
	}
	private final SArray arrayStruct = new SArray();

	class SReal3 extends Struct {
		Real x;
		Real y;
		Real z;
		Pad pad;
		void init() {
			x = real(precision);
			y = real(precision);
			z = real(precision);
			pad = pad(precision.map(4, 0));
			init(
				precision.map(16, 24),
				"x", "y", "z", "pad"
			);
		}
	}
	private final SReal3 real3Struct = new SReal3();

	class SPosInter extends Struct {
		Int32 posi1 = int32();
		Int32 posi2 = int32();
		Real weight;
		Real offset;
		void init() {
			weight = real(precision);
			offset = real(precision);
			init(
				precision.map(16, 24),
				"posi1", "posi2", "weight", "offset"
			);
		}
	}
	private final SPosInter posInterStruct = new SPosInter();

	class SDihedral extends Struct {
		Int64 id = int64();
		Real min_radians;
		Real max_radians;
		Int32 a_index = int32();
		Int32 b_index = int32();
		Int32 c_index = int32();
		Int32 d_index = int32();
		Int32 num_rotated = int32();
		Int32 modified_posi = int32();
		Pad pad;

		void init() {
			min_radians = real(precision);
			max_radians = real(precision);
			pad = pad(precision.map(8, 0));
			init(
				precision.map(48, 48),
				"id", "min_radians", "max_radians",
				"a_index", "b_index", "c_index", "d_index", "num_rotated",
				"modified_posi", "pad"
			);
		}

		long bytes(int numRotated) {
			return bytes() + padToGpuAlignment(Int32.bytes*numRotated);
		}
	}
	private final SDihedral dihedralStruct = new SDihedral();
	private static final int dihedralId = 0;

	public CudaConfEnergyCalculator(ConfSpace confSpace, Precision precision) {

		this.confSpace = confSpace;
		this.precision = precision;

		checkSupported();

		// find the forcefield implementation, or die trying
		EnergyCalculator.Type[] ecalcTypes = Arrays.stream(confSpace.ecalcs)
			.map(EnergyCalculator::type)
			.toArray(EnergyCalculator.Type[]::new);
		if (ecalcTypes.length == 2 && ecalcTypes[0] == AmberEnergyCalculator.type && ecalcTypes[1] == EEF1EnergyCalculator.type) {
			forcefieldsImpl = new AmberEef1();
		} else {
			throw new IllegalArgumentException("No native implementation for forcefields: " + Arrays.toString(ecalcTypes));
		}

		// once we know the precision, init the structs
		confSpaceStruct.init();
		posStruct.init();
		confStruct.init();
		arrayStruct.init();
		real3Struct.init();
		posInterStruct.init();
		dihedralStruct.init();

		Int64.Array posOffsets = int64array();
		Int64.Array confOffsets = int64array();
		Int64.Array posPairOffsets = int64array();
		Int64.Array fragOffsets = int64array();
		Int64.Array molMotionOffsets = int64array();
		Int64.Array confMotionOffsets = int64array();

		// calculate how much memory we need for the conf space buffer
		long confSpaceSize = confSpaceStruct.bytes()
			+ padToGpuAlignment(posOffsets.bytes(confSpace.positions.length));

		long positionsSize = sum(confSpace.positions, pos ->
			posStruct.bytes()
			+ padToGpuAlignment(confOffsets.bytes(pos.confs.length))
			+ sum(pos.confs, conf ->
				confStruct.bytes()
				+ arrayStruct.bytes()
				+ padToGpuAlignment(real3Struct.bytes()*conf.coords.size)
				+ padToGpuAlignment(confMotionOffsets.bytes(conf.motions.length))
				+ sum(conf.motions, motion -> {
					if (motion instanceof DihedralAngle.Description) {
						var dihedral = (DihedralAngle.Description)motion;
						return dihedralStruct.bytes(dihedral.rotated.length);
					} else {
						throw new UnsupportedOperationException(motion.getClass().getName());
					}
				})
			)
		);

		long staticCoordsSize = arrayStruct.bytes()
			+ padToGpuAlignment(real3Struct.bytes()*confSpace.staticCoords.size);

		long forcefieldSize = forcefieldsImpl.paramsBytes();

		// add space for the pos pair offsets
		int numPosPairs =
			1 // static-static
			+ confSpace.numPos() // static-pos
			+ confSpace.numPos() // pos
			+ confSpace.numPos()*(confSpace.numPos() - 1)/2; // pos-pos
		forcefieldSize += padToGpuAlignment(posPairOffsets.bytes(numPosPairs));

		// add space for the static-static pairs
		forcefieldSize += forcefieldsImpl.staticStaticBytes();

		// add space for the static-pos pairs and offsets
		for (int posi1=0; posi1<confSpace.positions.length; posi1++) {
			forcefieldSize += padToGpuAlignment(fragOffsets.itemBytes*confSpace.numFrag(posi1));
			for (int fragi1=0; fragi1<confSpace.numFrag(posi1); fragi1++) {
				forcefieldSize += forcefieldsImpl.staticPosBytes(posi1, fragi1);
			}
		}

		// add space for the pos pairs and offsets
		for (int posi1=0; posi1<confSpace.positions.length; posi1++) {
			forcefieldSize += padToGpuAlignment(fragOffsets.itemBytes*confSpace.numFrag(posi1));
			for (int fragi1=0; fragi1<confSpace.numFrag(posi1); fragi1++) {
				forcefieldSize += forcefieldsImpl.posBytes(posi1, fragi1);

			}
		}

		// add space for the pos-pos pairs and offsets
		for (int posi1=0; posi1<confSpace.positions.length; posi1++) {
			for (int posi2=0; posi2<posi1; posi2++) {
				forcefieldSize += padToGpuAlignment(fragOffsets.itemBytes*confSpace.numFrag(posi1)*confSpace.numFrag(posi2));
				for (int fragi1=0; fragi1<confSpace.numFrag(posi1); fragi1++) {
					for (int fragi2=0; fragi2<confSpace.numFrag(posi2); fragi2++) {
						forcefieldSize += forcefieldsImpl.posPosBytes(posi1, fragi1, posi2, fragi2);
					}
				}
			}
		}

		// TODO: molecule motions like translation/rotation

		// allocate the buffer for the conf space
		long bufSize = confSpaceSize + positionsSize + staticCoordsSize + forcefieldSize;
		try (MemorySegment confSpaceMem = MemorySegment.allocateNative(bufSize)) {
			BufWriter buf = new BufWriter(confSpaceMem);

			assert (buf.isAligned(WidestGpuLoad));

			// write the header
			buf.place(confSpaceStruct);
			confSpaceStruct.num_pos.set(confSpace.positions.length);
			confSpaceStruct.max_num_conf_atoms.set(confSpace.maxNumConfAtoms);
			confSpaceStruct.max_num_dofs.set(confSpace.maxNumDofs);
			confSpaceStruct.num_molecule_motions.set(0);
			confSpaceStruct.size.set(bufSize);
			// we'll go back and write the offsets later
			confSpaceStruct.static_energy.set(Arrays.stream(confSpace.staticEnergies).sum());

			assert (buf.isAligned(WidestGpuLoad));

			// leave space for the position offsets
			confSpaceStruct.positions_offset.set(buf.pos);
			buf.place(posOffsets, confSpace.positions.length, WidestGpuLoad);

			assert (buf.pos == confSpaceSize);
			assert (buf.isAligned(WidestGpuLoad));

			// write the positions
			for (ConfSpace.Pos pos : confSpace.positions) {

				assert (buf.isAligned(WidestGpuLoad));

				posOffsets.set(pos.index, buf.pos);
				buf.place(posStruct);
				posStruct.num_confs.set(pos.confs.length);
				posStruct.max_num_atoms.set(pos.maxNumAtoms);
				posStruct.num_frags.set(pos.numFrags);

				assert (buf.isAligned(WidestGpuLoad));

				// put the conf offsets
				buf.place(confOffsets, pos.confs.length, WidestGpuLoad);
				// we'll go back and write them later though

				assert (buf.isAligned(WidestGpuLoad));

				// write the confs
				for (ConfSpace.Conf conf : pos.confs) {

					assert (buf.isAligned(WidestGpuLoad));

					confOffsets.set(conf.index, buf.pos);
					buf.place(confStruct);
					confStruct.frag_index.set(conf.fragIndex);
					confStruct.internal_energy.set(Arrays.stream(conf.energies).sum());
					confStruct.num_motions.set(conf.motions.length);

					assert (buf.isAligned(WidestGpuLoad));

					// write the atoms
					confStruct.atoms_offset.set(buf.pos);
					buf.place(arrayStruct);
					arrayStruct.size.set(conf.coords.size);
					for (int i=0; i<conf.coords.size; i++) {
						buf.place(real3Struct);
						real3Struct.x.set(conf.coords.x(i));
						real3Struct.y.set(conf.coords.y(i));
						real3Struct.z.set(conf.coords.z(i));
					}
					buf.skipToAlignment(WidestGpuLoad);

					assert (buf.isAligned(WidestGpuLoad));

					// write the motions
					confStruct.motions_offset.set(buf.pos);
					buf.place(confMotionOffsets, conf.motions.length, WidestGpuLoad);
					for (int i=0; i<conf.motions.length; i++) {
						var motion = conf.motions[i];
						confMotionOffsets.set(i, buf.pos);
						if (motion instanceof DihedralAngle.Description) {
							writeDihedral((DihedralAngle.Description)motion, pos.index, buf);
						} else {
							throw new UnsupportedOperationException(motion.getClass().getName());
						}
					}

					assert (buf.isAligned(WidestGpuLoad));
				}
			}

			assert (buf.pos == confSpaceSize + positionsSize);
			assert (buf.isAligned(WidestGpuLoad));

			// write the static atoms
			confSpaceStruct.static_atoms_offset.set(buf.pos);
			buf.place(arrayStruct);
			arrayStruct.size.set(confSpace.staticCoords.size);
			for (int i=0; i<confSpace.staticCoords.size; i++) {
				buf.place(real3Struct);
				real3Struct.x.set(confSpace.staticCoords.x(i));
				real3Struct.y.set(confSpace.staticCoords.y(i));
				real3Struct.z.set(confSpace.staticCoords.z(i));
			}
			buf.skipToAlignment(WidestGpuLoad);

			assert (buf.pos == confSpaceSize + positionsSize + staticCoordsSize);
			assert (buf.isAligned(WidestGpuLoad));

			// write the forcefield params
			confSpaceStruct.params_offset.set(buf.pos);
			forcefieldsImpl.writeParams(buf);

			// write the pos pairs
			confSpaceStruct.pos_pairs_offset.set(buf.pos);
			buf.place(posPairOffsets, numPosPairs, WidestGpuLoad);

			assert (buf.isAligned(WidestGpuLoad));

			// write the static-static pair
			posPairOffsets.set(0, buf.pos);
			forcefieldsImpl.writeStaticStatic(buf);

			// write the static-pos pairs
			for (int posi1=0; posi1<confSpace.positions.length; posi1++) {
				posPairOffsets.set(1 + posi1, buf.pos);
				buf.place(fragOffsets, confSpace.numFrag(posi1), WidestGpuLoad);
				for (int fragi1=0; fragi1<confSpace.numFrag(posi1); fragi1++) {
					fragOffsets.set(fragi1, buf.pos);
					forcefieldsImpl.writeStaticPos(posi1, fragi1, buf);
				}
			}

			buf.skipToAlignment(WidestGpuLoad);

			// write the pos pairs
			for (int posi1=0; posi1<confSpace.positions.length; posi1++) {
				posPairOffsets.set(1 + confSpace.positions.length + posi1, buf.pos);
				buf.place(fragOffsets, confSpace.numFrag(posi1), WidestGpuLoad);
				for (int fragi1=0; fragi1<confSpace.numFrag(posi1); fragi1++) {
					fragOffsets.set(fragi1, buf.pos);
					forcefieldsImpl.writePos(posi1, fragi1, buf);
				}
			}

			buf.skipToAlignment(WidestGpuLoad);

			// write the pos-pos pairs
			for (int posi1=0; posi1<confSpace.positions.length; posi1++) {
				for (int posi2=0; posi2<posi1; posi2++) {
					posPairOffsets.set(1 + 2*confSpace.positions.length + posi1*(posi1 - 1)/2 + posi2, buf.pos);
					buf.place(fragOffsets, confSpace.numFrag(posi1)*confSpace.numFrag(posi2), WidestGpuLoad);
					for (int fragi1=0; fragi1<confSpace.numFrag(posi1); fragi1++) {
						for (int fragi2=0; fragi2<confSpace.numFrag(posi2); fragi2++) {
							fragOffsets.set(fragi1*confSpace.numFrag(posi2) + fragi2, buf.pos);
							forcefieldsImpl.writePosPos(posi1, fragi1, posi2, fragi2, buf);
						}
					}
				}
			}

			assert (buf.pos == confSpaceSize + positionsSize + staticCoordsSize + forcefieldSize);
			buf.skipToAlignment(WidestGpuLoad);

			// make sure we used the whole buffer
			assert(buf.pos == bufSize) : String.format("%d bytes leftover", bufSize - buf.pos);

			// upload the conf space to the GPU
			pConfSpace = switch (precision) {
				case Float32 -> NativeLib.alloc_conf_space_f32(confSpaceMem.asByteBuffer());
				case Float64 -> NativeLib.alloc_conf_space_f64(confSpaceMem.asByteBuffer());
			};
		}
	}

	private void writeDihedral(DihedralAngle.Description desc, int posi, BufWriter buf) {

		buf.place(dihedralStruct);
		dihedralStruct.id.set(dihedralId);
		dihedralStruct.min_radians.set(Math.toRadians(desc.minDegrees));
		dihedralStruct.max_radians.set(Math.toRadians(desc.maxDegrees));
		dihedralStruct.a_index.set(desc.getAtomIndex(confSpace, posi, desc.a));
		dihedralStruct.b_index.set(desc.getAtomIndex(confSpace, posi, desc.b));
		dihedralStruct.c_index.set(desc.getAtomIndex(confSpace, posi, desc.c));
		dihedralStruct.d_index.set(desc.getAtomIndex(confSpace, posi, desc.d));
		dihedralStruct.num_rotated.set(desc.rotated.length);
		dihedralStruct.modified_posi.set(posi);

		var rotatedIndices = int32array();
		buf.place(rotatedIndices, desc.rotated.length, WidestGpuLoad);
		for (int i=0; i<desc.rotated.length; i++) {
			rotatedIndices.set(i, desc.getAtomIndex(confSpace, posi, desc.rotated[i]));
		}

		buf.skipToAlignment(WidestGpuLoad);
	}

	@Override
	public Precision precision() {
		return precision;
	}

	public String version() {
		return String.format("%d.%d", NativeLib.version_major(), NativeLib.version_minor());
	}

	public AssignedCoords assign(int[] conf) {
		try (var confMem = makeConf(conf)) {
			try (var coordsMem = makeArray(confSpace.maxNumConfAtoms, real3Struct.bytes())) {
				switch (precision) {
					case Float32 -> NativeLib.assign_f32(pConfSpace, confMem.asByteBuffer(), coordsMem.asByteBuffer());
					case Float64 -> NativeLib.assign_f64(pConfSpace, confMem.asByteBuffer(), coordsMem.asByteBuffer());
				}
				return makeCoords(coordsMem, conf);
			}
		}
	}

	private MemorySegment makeConf(int[] conf) {

		MemorySegment mem = makeArray(confSpace.positions.length, Int32.bytes);
		var array = int32array();
		array.setAddress(getArrayAddress(mem));
		for (int posi=0; posi<confSpace.positions.length; posi++) {
			array.set(posi, conf[posi]);
		}

		return mem;
	}

	private AssignedCoords makeCoords(MemorySegment mem, int[] assignments) {

		AssignedCoords coords = new AssignedCoords(confSpace, assignments);

		// copy the coords from the native memory
		var addr = getArrayAddress(mem);
		for (int i=0; i<confSpace.maxNumConfAtoms; i++) {
			real3Struct.setAddress(addr.addOffset(i*real3Struct.bytes()));
			coords.coords.set(
				i,
				real3Struct.x.get(),
				real3Struct.y.get(),
				real3Struct.z.get()
			);
		}

		return coords;
	}

	@Override
	public void close() {
		NativeLib.free_conf_space(pConfSpace);
	}

	@Override
	public ConfSpace confSpace() {
		return confSpace;
	}

	@Override
	public EnergiedCoords calc(int[] conf, List<PosInter> inters) {
		try (var confMem = makeConf(conf)) {
			try (var intersMem = makeIntersMem(inters)) {
				try (var coordsMem = makeArray(confSpace.maxNumConfAtoms, real3Struct.bytes())) {
					double energy = forcefieldsImpl.calc(pConfSpace, confMem.asByteBuffer(), intersMem.asByteBuffer(), coordsMem.asByteBuffer(), confSpace.maxNumConfAtoms);
					return new EnergiedCoords(
						makeCoords(coordsMem, conf),
						energy
					);
				}
			}
		}
	}

	@Override
	public double calcEnergy(int[] conf, List<PosInter> inters) {
		try (var confMem = makeConf(conf)) {
			try (var intersMem = makeIntersMem(inters)) {
				return forcefieldsImpl.calc(pConfSpace, confMem.asByteBuffer(), intersMem.asByteBuffer(), null, confSpace.maxNumConfAtoms);
			}
		}
	}

	private DoubleMatrix1D makeDofs(MemorySegment mem) {

		int size = (int)getArraySize(mem);
		DoubleMatrix1D vals = DoubleFactory1D.dense.make(size);

		Real.Array floats = realarray(precision);
		floats.setAddress(getArrayAddress(mem));
		for (int i=0; i<size; i++) {
			vals.set(i, floats.get(i));
		}

		return vals;
	}

	@Override
	public EnergiedCoords minimize(int[] conf, List<PosInter> inters) {
		/* TODO
		try (var intersMem = makeIntersMem(inters)) {
			try (var coordsMem = makeArray(confSpace.maxNumConfAtoms, real3Struct.bytes())) {
				try (var dofsMem = makeArray(confSpace.maxNumDofs, precision.bytes)) {
					double energy;
					try (var confSpaceMem = this.confSpaceMem.acquire()) {
						energy = forcefieldsImpl.minimize(
							confSpaceMem.asByteBuffer(), conf,
							intersMem.asByteBuffer(),
							coordsMem.asByteBuffer(), dofsMem.asByteBuffer()
						);
					}
					return new EnergiedCoords(
						makeCoords(coordsMem, conf),
						energy,
						makeDofs(dofsMem)
					);
				}
			}
		}
		*/
		throw new Error("TODO");
	}

	@Override
	public double minimizeEnergy(int[] conf, List<PosInter> inters) {
		try (var confMem = makeConf(conf)) {
			try (var intersMem = makeIntersMem(inters)) {
				return forcefieldsImpl.minimize(pConfSpace, confMem.asByteBuffer(), intersMem.asByteBuffer(), null, confSpace.maxNumConfAtoms, null, confSpace.maxNumDofs);
			}
		}
	}

	private MemorySegment makeIntersMem(List<PosInter> inters) {
		MemorySegment mem = makeArray(inters.size(), posInterStruct.bytes());
		BufWriter buf = new BufWriter(mem);
		buf.pos = getArrayAddress(mem).offset();
		for (var inter : inters) {
			buf.place(posInterStruct);
			posInterStruct.posi1.set(inter.posi1);
			posInterStruct.posi2.set(inter.posi2);
			posInterStruct.weight.set(inter.weight);
			posInterStruct.offset.set(inter.offset);
		}
		return mem;
	}

	// helpers for the Array class on the c++ size

	private MemorySegment makeArray(long size, long itemBytes) {
		MemorySegment mem = MemorySegment.allocateNative(Int64.bytes*2 + size*itemBytes);
		BufWriter buf = new BufWriter(mem);
		buf.int64(size);
		buf.skip(8); // padding
		return mem;
	}

	private long getArraySize(MemorySegment mem) {
		var h = MemoryHandles.varHandle(long.class, ByteOrder.nativeOrder());
		return (long)h.get(mem.baseAddress());
	}

	private MemoryAddress getArrayAddress(MemorySegment mem) {
		return mem.baseAddress().addOffset(Int64.bytes*2);
	}
}
