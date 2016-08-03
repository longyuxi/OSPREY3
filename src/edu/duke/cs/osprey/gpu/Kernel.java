package edu.duke.cs.osprey.gpu;

import java.io.IOException;

import com.jogamp.opencl.CLKernel;

public abstract class Kernel<T extends BoundKernel<T>> {
	
	private CLKernel kernel;
	
	public Kernel(String filename, String name)
	throws IOException {
		kernel = Gpus.get().getProgram(filename).createCLKernel(name);
	}
	
	public CLKernel getCLKernel() {
		return kernel;
	}
	
	public T bind() {
		return bind(false);
	}
	
	public T bind(boolean useProfiling) {
		return bind(Gpus.get().getBestGpu(), useProfiling);
	}
	
	public abstract T bind(Gpu gpu, boolean useProfiling);
	
	public void cleanup() {
		kernel.release();
	}
}
