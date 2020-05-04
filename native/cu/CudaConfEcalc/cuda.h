
#ifndef CUDACONFECALC_CUDA_H
#define CUDACONFECALC_CUDA_H


#include <cooperative_groups.h>

namespace cg = cooperative_groups;


namespace osprey {

	// use cuda vector types for Real3

	template<typename T>
	struct Real3Map {
		typedef void type;
		const static size_t size;
	};

	template<>
	struct Real3Map<float32_t> {
		typedef float3 type;

		// yes, a float3 is only 12 bytes,
		// but actually loading exactly 3 floats requires 2 load instructions
		// eg in PTX: ld.global.v2.f32 and ld.global.f32
		// so pretend a float3 is 16 bytes so we can use 1 load instruction
		// eg in PTX: ld.global.v4.f32
		const static size_t size = 16;
	};

	template<>
	struct Real3Map<float64_t> {
		typedef double3 type;
		const static size_t size = 24;
	};

	template<typename T>
	using Real3 = typename Real3Map<T>::type;

	// these are the sizes and alignments the compiler actually uses
	static_assert(sizeof(Real3<float32_t>) == 12);
	static_assert(alignof(Real3<float32_t>) == 4);

	static_assert(sizeof(Real3<float64_t>) == 24);
	static_assert(alignof(Real3<float64_t>) == 8);


	// add math functions for Real3, since CUDA apparently doesn't have them in the stdlib ;_;

	template<typename T>
	__device__
	inline T dot(const Real3<T> & a, const Real3<T> & b) {
		return a.x*b.x + a.y*b.y + a.z*b.z;
	}

	template<typename T>
	__device__
	inline T distance_sq(const Real3<T> & a, const Real3<T> & b) {
		T dx = a.x - b.x;
		T dy = a.y - b.y;
		T dz = a.z - b.z;
		return dx*dx + dy*dy + dz*dz;
	}
}


namespace cuda {

	__host__
	void check_error();

	__host__
	int optimize_threads_void(const void * func, size_t shared_size_static, size_t shared_size_per_thread);

	// pick the greatest number of the threads that keeps occupancy above 0
	template<typename T>
	int optimize_threads(const T & func, size_t shared_size_static, size_t shared_size_per_thread) {
		return optimize_threads_void(reinterpret_cast<const void *>(&func), shared_size_static, shared_size_per_thread);
	}
}


#define PRINTF0(threads, fmt, ...) \
	if (threads.thread_rank() == 0) { \
		printf(fmt, __VA_ARGS__); \
	}


#endif //CUDACONFECALC_CUDA_H
