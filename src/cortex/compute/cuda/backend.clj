(ns cortex.compute.cuda.backend
  (:require [cortex.compute.javacpp-datatype :as jcpp-dtype]
            [cortex.compute.nn.backend :as nn-backend]
            [cortex.compute.nn.protocols :as compute-protocols]
            [cortex.compute.nn.layers :as compute-layers]
            [cortex.compute.math :as math]
            [cortex.compute.driver :as drv]
            [cortex.graph :as graph]
            [cortex.compute.cpu.backend :as cpu-backend]
            [cortex.optimize :as opt]
            [think.datatype.core :as dtype]
            [think.datatype.base :as dtype-base]
            [think.resource.core :as resource]
            [cortex.compute.cuda.driver :refer [->ptr value->ptr] :as cuda-drv]
            [cortex.compute.cuda.tensor-math])
  (:import [org.bytedeco.javacpp cudnn cudnn$cudnnContext cudnn$cudnnTensorStruct
            cudnn$cudnnActivationStruct cudnn$cudnnConvolutionStruct cudnn$cudnnFilterStruct
            cudnn$cudnnPoolingStruct cudnn$cudnnLRNStruct
            BytePointer IntPointer LongPointer DoublePointer Pointer PointerPointer
            SizeTPointer FloatPointer ShortPointer]
           [cortex.compute.cuda.driver CudaDriver CudaStream]
           [cortex.compute.math DeviceArray]))


(set! *warn-on-reflection* true)




(extend-protocol resource/PResource)


(defrecord CudaBackend [type device stream datatype network-functions]
  resource/PResource
  (release-resource
    [backend]
    (drv/with-compute-device (get backend :device)
      (resource/release-resource-context (get backend :resource-context))))
  drv/PDeviceProvider
  (get-device
    [backend]
    (get backend :device))
  drv/PStreamProvider
  (get-stream
    [backend]
    (get backend :stream))
  drv/PDriverProvider
  (get-driver
    [backend]
    (drv/get-driver (drv/get-device backend)))
  dtype-base/PDatatype
  (get-datatype
    [backend]
    (get backend :datatype)))


(defn backend
  [& {:keys [driver device datatype stream]
      :or {datatype :float}}]
  (let [driver (or driver (cuda-drv/driver))
        device (or device (drv/default-device driver))]
    ;;Do not use with device as that enforces a resource context.  This means
    ;;the backend would be destroyed as it left this function.
    ;;Using the unsafe function means are a explicitly relying on an outer resource
    ;;context to handle release of this backend.
    (drv/unsafe-with-compute-device
     device
     (let [[backend res-ctx]
            (resource/return-resource-context
             (let [network-functions {:prepare-bernoulli-dropout
                                      (cuda-drv/load-float-double-function
                                       "prepare_bernoulli_dropout.fatbin"
                                       "prepare_bernoulli_dropout")
                                      :prepare-gaussian-dropout
                                      (cuda-drv/load-float-double-function
                                       "prepare_gaussian_dropout.fatbin"
                                       "prepare_gaussian_dropout")}
                   default-stream (or stream (drv/create-stream))]
               (->CudaBackend :cuda device default-stream datatype network-functions)))]
        (resource/track (assoc backend :resource-context res-ctx))))))


(defn get-cudnn
  ^cudnn$cudnnContext [^CudaBackend network]
  (when (not (identical? (.device network)
                         (cuda-drv/current-cuda-device)))
    (throw (ex-info "Backend device and current cuda device do not match!"
                    {})))
  (cuda-drv/get-cudnn))


(defmacro stream-with-cudnn
  [backend & body]
  `(let [backend# ~backend
         stream# (nn-backend/get-stream)]
     (cuda-drv/cudnn-with-stream stream# ~@body)))


(defprotocol PCUDAOptimizeMethod
  (cuda-prepare-bernoulli-dropout! [mult-buffer probability rand-buffer elem-count backend])
  (cuda-prepare-gaussian-dropout! [mult-buffer rand-buffer elem-count backend]))


(defn backend->fn
  [impl fn-name datatype]
  (get-in impl [:network-functions fn-name datatype :fn]))


(extend-type DoublePointer
  PCUDAOptimizeMethod
  (cuda-prepare-bernoulli-dropout! [mult-buffer probability
                                    ^FloatPointer rand-buffer elem-count backend]
    (cuda-drv/launch-linear-kernel (nn-backend/get-stream)
                                   (backend->fn backend :prepare-bernoulli-dropout :double)
                                   elem-count 0
                                   mult-buffer rand-buffer (double probability) elem-count))
  (cuda-prepare-gaussian-dropout! [mult-buffer rand-buffer elem-count backend]
    (cuda-drv/launch-linear-kernel (nn-backend/get-stream)
                                   (backend->fn backend :prepare-gaussian-dropout :double)
                                   elem-count 0
                                   mult-buffer rand-buffer elem-count)))


(extend-type FloatPointer
  PCUDAOptimizeMethod
  (cuda-prepare-bernoulli-dropout! [mult-buffer probability ^FloatPointer rand-buffer elem-count backend]
    (cuda-drv/launch-linear-kernel (nn-backend/get-stream) (backend->fn backend :prepare-bernoulli-dropout :float) elem-count 0
                                   mult-buffer rand-buffer (float probability) elem-count))
  (cuda-prepare-gaussian-dropout! [mult-buffer rand-buffer elem-count backend]
    (cuda-drv/launch-linear-kernel (nn-backend/get-stream) (backend->fn backend :prepare-gaussian-dropout :float) elem-count 0
                                   mult-buffer rand-buffer elem-count)))


(defn layer->flat-tensor
  ^cudnn$cudnnTensorStruct [layer batch-size datatype]
  (cuda-drv/tensor datatype 1 1 1 (* (long batch-size) (long (graph/node->output-size layer)))))


(defn layer-input->image-tensor
  ^cudnn$cudnnTensorStruct [layer batch-size datatype]
  (let [{:keys [channels width height]} (first (graph/node->input-dimensions layer))]
    (cuda-drv/tensor datatype batch-size channels height width)))


(defn layer-output->image-tensor
  ^cudnn$cudnnTensorStruct [layer batch-size datatype]
  (let [{:keys [channels width height]} (first (graph/node->output-dimensions layer))]
    (cuda-drv/tensor datatype batch-size channels height width)))


(defn first-buffer
  [buffer-list]
  (->ptr (get-in buffer-list [0 :buffer])))


(defn first-gradient
  [buffer-list]
  (->ptr (get-in buffer-list [0 :gradient])))


(defmulti cuda-layer
  "General function to create a layer implemented completely by the cuda backend"
  (fn [backend layer batch-size]
    (get layer :type)))


(defrecord PoolingLayer [backend
                         ^cudnn$cudnnTensorStruct input-tensor
                         ^cudnn$cudnnTensorStruct output-tensor
                         ^cudnn$cudnnPoolingStruct pooling-descriptor]
  compute-protocols/ComputeLayer
  (forward [this parameter-buffers input-buffers output-buffers]
    (stream-with-cudnn
     backend
     (let [datatype (dtype/get-datatype backend)]
       (cuda-drv/cudnn-call (cudnn/cudnnPoolingForward
                    cudnn-context
                    pooling-descriptor
                    (value->ptr 1 datatype)
                    input-tensor
                    (first-buffer input-buffers)
                    (value->ptr 0 datatype)
                    output-tensor
                    (first-buffer output-buffers))))))
  (backward [this parameter-buffers output-buffers input-buffers]
    (stream-with-cudnn
     backend
     (let [datatype (dtype/get-datatype backend)]
       (cuda-drv/cudnn-call
        (cudnn/cudnnPoolingBackward
         cudnn-context
         pooling-descriptor
         (value->ptr 1 datatype)
         output-tensor
         (first-buffer output-buffers)
         output-tensor
         (first-gradient output-buffers)
         input-tensor
         (first-buffer input-buffers)
         (value->ptr 0 datatype)
         input-tensor
         (first-gradient input-buffers)))))))

(defn conv-type-layer->conv-config
  "Backwards compatibility function necessary as the node format has changed over time."
  [conv-layer]
  (let [input-dims (first (graph/node->input-dimensions conv-layer))
        output-dims (first (graph/node->output-dimensions conv-layer))]
    (assoc conv-layer
           :input-channels (get input-dims :channels)
           :input-width (get input-dims :width)
           :input-height (get input-dims :height)
           :input-size (graph/dimensions->size input-dims)
           :output-channels (get output-dims :channels)
           :output-width (get output-dims :width)
           :output-height (get output-dims :height)
           :output-size (graph/dimensions->size output-dims))))


(defmethod cuda-layer :max-pooling
  [backend layer ^long batch-size]
  (let [layer (conv-type-layer->conv-config layer)
        pooling-desc (cudnn$cudnnPoolingStruct.)
        output-width (get layer :output-width)
        output-height (get layer :output-height)
        datatype (dtype/get-datatype backend)
        cudnn-dtype (cuda-drv/dtype->cudnn datatype)
        input-tensor (layer-input->image-tensor layer batch-size datatype)
        output-tensor (layer-output->image-tensor layer batch-size datatype)
        output-dims (int-array 4)
        pool-op (get layer :pool-op :max)]
    (cuda-drv/cudnn-call (cudnn/cudnnCreatePoolingDescriptor pooling-desc))
    (resource/track pooling-desc)
    (cuda-drv/cudnn-call (cudnn/cudnnSetPooling2dDescriptor
                 pooling-desc
                 (condp = pool-op
                   :max cudnn/CUDNN_POOLING_MAX
                   :avg cudnn/CUDNN_POOLING_AVERAGE_COUNT_INCLUDE_PADDING
                   :avg-exc-pad cudnn/CUDNN_POOLING_AVERAGE_COUNT_EXCLUDE_PADDING)
                 cudnn/CUDNN_PROPAGATE_NAN
                 (:kernel-height layer) (:kernel-width layer)
                 (:pad-y layer) (:pad-x layer)
                 (:stride-y layer) (:stride-x layer)))
    ;;These do not have to match; cudnn can take care of it if they are off.
    ;;https://devtalk.nvidia.com/default/topic/949999/cuda-programming-and-performance/cudnn-calculates-layer-sizes-different-than-caffe/
    (comment
      (cuda-drv/cudnn-call (cudnn/cudnnGetPoolingNdForwardOutputDim
                   pooling-desc
                   input-tensor
                   4
                   output-dims))

      (let [[n c h w] output-dims]
        (when-not (and (= output-width w)
                       (= output-height h))
          (throw (Exception. (format "Pooling layer size mismatch: cudnn %s calculated %s"
                                     [w h]
                                     [output-width output-height]))))))
    (map->PoolingLayer
     {:backend backend
      :input-tensor input-tensor
      :output-tensor output-tensor
      :pooling-descriptor pooling-desc})))


(defn- lrn-descriptor
    [backend n k alpha beta]
    (let [desc (cudnn$cudnnLRNStruct.)]
      (cuda-drv/cudnn-call (cudnn/cudnnCreateLRNDescriptor desc))
      (cuda-drv/cudnn-call (cudnn/cudnnSetLRNDescriptor desc
                                               (int n)
                                               (double alpha)
                                               (double beta)
                                               (double k)))
      (resource/track desc)))


  ;; From the cudnn documentation:
  ;; Value of the alpha variance scaling parameter in the normalization formula. Inside
  ;; the library code this value is divided by the window width for LRN and by
  ;; (window width)^#spatialDimensions for DivisiveNormalization. By default this value is set to
  ;; 1e-4 in cudnnCreateLRNDescriptor.
  (defrecord LocalResponseNormalization [backend
                                         ^cudnn$cudnnLRNStruct lrn-desc
                                         ^cudnn$cudnnTensorStruct data-tensor
                                         datatype]
    compute-protocols/ComputeLayer
    (forward [layer parameter-bufers input-buffers output-buffers]
      (stream-with-cudnn
       backend
       (cuda-drv/cudnn-call (cudnn/cudnnLRNCrossChannelForward
                    cudnn-context
                    lrn-desc
                    cudnn/CUDNN_LRN_CROSS_CHANNEL_DIM1
                    (value->ptr 1.0 datatype)
                    data-tensor
                    (first-buffer input-buffers)
                    (value->ptr 0.0 datatype)
                    data-tensor
                    (first-buffer output-buffers)))))

    (backward [layer parameter-buffers output-buffers input-buffers]
      (stream-with-cudnn
       backend
       (cuda-drv/cudnn-call (cudnn/cudnnLRNCrossChannelBackward
                    cudnn-context
                    lrn-desc
                    cudnn/CUDNN_LRN_CROSS_CHANNEL_DIM1
                    (value->ptr 1.0 datatype)
                    data-tensor
                    (first-buffer output-buffers)
                    data-tensor
                    (first-gradient output-buffers)
                    data-tensor
                    (first-buffer input-buffers)
                    (value->ptr 0.0 datatype)
                    data-tensor
                    (first-gradient input-buffers))))))


(defmethod cuda-layer :local-response-normalization
  [backend layer batch-size]
  (let [{:keys [input-width input-height input-channels n k alpha beta]} layer
        data-tensor (layer-output->image-tensor layer batch-size (dtype/get-datatype backend))
        lrn-desc (lrn-descriptor backend n k alpha beta)]
    (->LocalResponseNormalization backend lrn-desc data-tensor
                                  (dtype/get-datatype backend))))



(extend-type CudaBackend
  nn-backend/PLayerCreation
  (create [backend layer batch-size]
    (cuda-layer backend layer batch-size))

  nn-backend/PDropout
  (prepare-bernoulli-dropout! [backend probability rand-buffer mult-buffer]
    (cuda-prepare-bernoulli-dropout! (->ptr mult-buffer) probability
                                     (->ptr rand-buffer) (math/ecount mult-buffer) backend))
  (prepare-gaussian-dropout! [backend rand-buffer mult-buffer]
    (cuda-prepare-gaussian-dropout! (->ptr mult-buffer)
                                    (->ptr rand-buffer) (math/ecount mult-buffer) backend)))
