/*
 * AbstractConvolutionD
 *
 * Copyright (c) 2014 Renato Villone
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.villoren.java.dsp.convolution;

import com.villoren.java.dsp.fft.ComplexBufferD;
import com.villoren.java.dsp.fft.FourierTransformD;
import com.villoren.java.dsp.window.AbstractWindowD;
import com.villoren.java.dsp.window.BlackmanWindowD;

/**
 * Provides a base class for Real and Complex convolutions.
 * <p>
 * The intention of this class is to implement methods that are common among specific implementations.
 * It is <i>not</i> intended to provide a polymorphic solution such as in:
 * <p><code>AbstractConvolution myConvolution = new ConvolutionImplementation()</code></p>
 * since those implementations don't share a common interface to perform the actual convolution.
 *
 * @author Renato Villone
 */
public abstract class AbstractConvolutionD {

    /**
     * Number of complex samples to be convolved in each step.
     * Note that RealConvolution accepts mSize * 2 samples in each step.
     */
    protected final int mSize;

    /**
     * Number of complex samples in each fft pass.
     * FTT size will be twice {@link #mSize} to accommodate:
     * <code>mSize * input samples + mWindowSize * filter kernel - 1</code>
     * and prevent circular convolution.
     */
    protected final int mFftSize;

    /**
     * Size of the window, that is, actual <i>usable</i> size of the kernel's impulse response.
     * Non zero data at and beyond this index will result in circular convolution.
     */
    private final int mWindowSize;

    /**
     * Fixed sized FFT instance.
     */
    private final FourierTransformD mFourierTransform;

    /**
     * Default window to be used by <code>FilterKernel</code>.
     */
    private AbstractWindowD mDefaultWindow;

    /**
     * Frequency response of this convolution.
     */
    private FrequencyResponseD mFrequencyResponse;

    /**
     * Listener provided by client.
     */
    private OnConvolveListenerD mListener;

    /**
     * The zero-padded input signal transformed to the frequency domain.
     */
    private final ComplexBufferD mPreConvolutionFreqDomain;

    /**
     * {@link #mPreConvolutionFreqDomain} after the convolution.
     */
    private final ComplexBufferD mPostConvolutionFreqDomain;

    /**
     * Constructor called by subclasses.
     *
     * @param size Number of samples to process in each convolution.
     */
    public AbstractConvolutionD(int size) {

        // Sizes
        mSize = size;
        mFftSize = mSize * 2;
        mWindowSize = mSize + 1; // Since result size = number of samples + kernel size - 1

        // Tools
        mFourierTransform = new FourierTransformD(mFftSize, FourierTransformD.Scale.INVERSE);
        mDefaultWindow = new BlackmanWindowD(mWindowSize);

        // Filter
        mFrequencyResponse = new FrequencyResponseD(this);

        // Transitional data
        mPreConvolutionFreqDomain = new ComplexBufferD(mFftSize);
        mPostConvolutionFreqDomain = new ComplexBufferD(mFftSize);
    }

    /**
     * Sets a <code>FilterKernel</code> to be used by this <code>Convolution</code>.
     * <p>
     * To prevent aliasing, the kernel's impulse response should be:
     * <li> <code>getFftSize()</code> / 2 + 1 samples long </li>
     * <li> centered around <code>getFftSize()</code> / 4 (ie in the first half) </li>
     * <li> windowed by a window-sinc</li>
     * </ul>
     * To prevent circular convolution, remaining samples (to the right of the impulse response) should be zero.
     * <p>
     * <b>Note:</b> This requirements are automatically met when setting a <code>FrequencyResponse</code>
     * to a <code>FilterKernel</code>.
     * <p>
     * In most situations, you might also want to set al <code>filterKernel.imag[]</code> to zero,
     * for instance, to be able to convolve two distinct signals in each convolution step, such as when
     * convolving stereo audio signals.
     *
     * @param filterKernel The kernel to be used by this convolution.
     * @see FilterKernelD#setFrequencyResponse(FrequencyResponseD)
     */
    public void setFilterKernel(FilterKernelD filterKernel) {

        ensure(this == filterKernel.getConvolution(),
                "setFilterKernel", "filterKernel was created for another Convolution instance.");

        mFrequencyResponse.setFilterKernel(filterKernel);
    }

    /**
     * Creates a copy of the <code>FilterKernel</code> currently in use by this convolution.
     *
     * @return Copy of this Convolution's <code>FilterKernel</code>.
     */
    public FilterKernelD getFilterKernel() {

        FilterKernelD result = new FilterKernelD(this);
        result.setFrequencyResponse(mFrequencyResponse);

        return result;
    }

    /**
     * Sets a listener to handle in-convolution callbacks.
     * <p>
     * Used to monitor the frequency domain part of the convolution.
     * <p>
     * If <code>listener == null</code>, callbacks are disabled.
     *
     * @param listener An implementation of <code>OnConvolveListener</code> to handle the callbacks
     *                 or <code>null</code> to disable this mechanism.
     */
    public void setOnConvolveListener(OnConvolveListenerD listener) {

        mListener = listener;
    }

    /**
     * Sets the class implementing <code>AbstractWindow</code>
     * to be used by default by <code>FilterKernel</code>.
     *
     * @param windowClass A class extending <code>AbstractWindow</code>.
     */
    public void setDefaultWindowClass(Class<? extends AbstractWindowD> windowClass) {

        try {

            mDefaultWindow = windowClass.getConstructor(int.class).newInstance(mWindowSize);

        } catch (Exception ex) { ex.printStackTrace(); }
    }

    /**
     * @return Number of samples to process in each <code>convolve()</code> pass.
     */
    public int getSize() { return mSize; }

    /**
     * @return FFT size in each <code>convolve()</code> pass.
     */
    public int getFftSize() { return mFftSize; }

    /**
     * @return Size of the Window-sinc used by the <code>FilterKernel</code>. Equals the size of the impulse response.
     */
    public int getWindowSize() { return mWindowSize; }

    /**
     * @return FFT instance used by this <code>Convolution</code>.
     */
    FourierTransformD getFourierTransform() { return mFourierTransform; }

    /**
     * @return Default window to be used by <code>FilterKernel</code>
     */
    AbstractWindowD getDefaultWindow() { return mDefaultWindow; }

    /**
     * Convolves the input signal in <code>inTimeDomain</code> with the previously
     * gathered {@link FrequencyResponseD}
     * from the given {@link FilterKernelD}.
     *
     * @param inTimeDomain Buffer of {@link #mFftSize}. This method assumes the buffer is filled with
     *                     time-domain samples up to half its size, and the second half is already zero-padded.
     * @param outTimeDomain Buffer of {@link #mFftSize}. Upon return, contains the convolved signal.
     */
    protected void convolveFreqDomain(ComplexBufferD inTimeDomain, ComplexBufferD outTimeDomain) {

        // Convert zero-padded arrays to frequency domain
        mFourierTransform.transform(inTimeDomain, mPreConvolutionFreqDomain, false);

        // Pre-convolution callback
        if (mListener != null)
            mListener.onPreConvolve(inTimeDomain, mPreConvolutionFreqDomain);

        // Convolve
        mPostConvolutionFreqDomain.cross(mPreConvolutionFreqDomain, mFrequencyResponse);

        // Return to time domain
        mFourierTransform.transform(mPostConvolutionFreqDomain, outTimeDomain, true);

        // Post-convolution callback
        if (mListener != null)
            mListener.onPostConvolve(outTimeDomain, mPostConvolutionFreqDomain);
    }

    /**
     * Generic method to assert a condition.
     *
     * @param condition Condition to check.
     * @param method Calling method.
     * @param error Error message if condition not met.
     */
    protected void ensure(boolean condition, String method, String error) {

        if (!condition) {

            String msg = String.format("%s.%s(): %s", getClass().getSimpleName(), method, error);
            throw new IllegalArgumentException(msg);
        }
    }
}
