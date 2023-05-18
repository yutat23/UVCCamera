/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.encoder;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

public abstract class MediaEncoder implements Runnable {
	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = "MediaEncoder";

	protected static final int TIMEOUT_USEC = 10000;	// 10[msec]
	protected static final int MSG_FRAME_AVAILABLE = 1;
	protected static final int MSG_STOP_RECORDING = 9;

	public interface MediaEncoderListener {
		public void onPrepared(MediaEncoder encoder);
		public void onStopped(MediaEncoder encoder);
	}

	protected final Object mSync = new Object();
	/**
	 * Flag that indicate this encoder is capturing now.
	 */
    protected volatile boolean mIsCapturing;
	/**
	 * Flag that indicate the frame data will be available soon.
	 */
	private int mRequestDrain;
    /**
     * Flag to request stop capturing
     */
    protected volatile boolean mRequestStop;
    /**
     * Flag that indicate encoder received EOS(End Of Stream)
     */
    protected boolean mIsEOS;
    /**
     * Flag the indicate the muxer is running
     */
    protected boolean mMuxerStarted;
    /**
     * Track Number
     */
    protected int mTrackIndex;
    /**
     * MediaCodec instance for encoding
     */
    public MediaCodec mMediaCodec;				// API >= 16(Android4.1.2)
    /**
     * Weak refarence of MediaMuxerWarapper instance
     */
    protected final WeakReference<MediaMuxerWrapper> mWeakMuxer;
    /**
     * BufferInfo instance for dequeuing
     */
    private MediaCodec.BufferInfo mBufferInfo;		// API >= 16(Android4.1.2)

    protected final MediaEncoderListener mListener;

    public MediaEncoder(final MediaMuxerWrapper muxer, final MediaEncoderListener listener) {
    	if (listener == null) throw new NullPointerException("MediaEncoderListener is null");
    	if (muxer == null) throw new NullPointerException("MediaMuxerWrapper is null");
		mWeakMuxer = new WeakReference<MediaMuxerWrapper>(muxer);
		muxer.addEncoder(this);
		mListener = listener;
        synchronized (mSync) {
            // create BufferInfo here for effectiveness(to reduce GC)
            mBufferInfo = new MediaCodec.BufferInfo();
            // wait for starting thread
            new Thread(this, getClass().getSimpleName()).start();
            try {
            	mSync.wait();
            } catch (final InterruptedException e) {
            }
        }
		startServer();
	}

    public String getOutputPath() {
    	final MediaMuxerWrapper muxer = mWeakMuxer.get();
    	return muxer != null ? muxer.getOutputPath() : null;
    }

    /**
     * the method to indicate frame data is soon available or already available
     * @return return true if encoder is ready to encod.
     */
    public boolean frameAvailableSoon() {
//    	if (DEBUG) Log.v(TAG, "frameAvailableSoon");
        synchronized (mSync) {
            if (!mIsCapturing || mRequestStop) {
                return false;
            }
            mRequestDrain++;
            mSync.notifyAll();
        }
        return true;
    }

    /**
     * encoding loop on private thread
     */
	@Override
	public void run() {
//		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        synchronized (mSync) {
            mRequestStop = false;
    		mRequestDrain = 0;
            mSync.notify();
        }
        final boolean isRunning = true;
        boolean localRequestStop;
        boolean localRequestDrain;
        while (isRunning) {
        	synchronized (mSync) {
        		localRequestStop = mRequestStop;
        		localRequestDrain = (mRequestDrain > 0);
        		if (localRequestDrain)
        			mRequestDrain--;
        	}
	        if (localRequestStop) {
	           	drain();
	           	// request stop recording
	           	signalEndOfInputStream();
	           	// process output data again for EOS signale
	           	drain();
	           	// release all related objects
	           	release();
	           	break;
	        }
	        if (localRequestDrain) {
	        	drain();
	        } else {
	        	synchronized (mSync) {
		        	try {
						mSync.wait();
					} catch (final InterruptedException e) {
						break;
					}
	        	}
        	}
        } // end of while
		if (DEBUG) Log.d(TAG, "Encoder thread exiting");
        synchronized (mSync) {
        	mRequestStop = true;
            mIsCapturing = false;
        }
	}

	/*
    * prepareing method for each sub class
    * this method should be implemented in sub class, so set this as abstract method
    * @throws IOException
    */
   /*package*/ abstract void prepare() throws IOException;

	/*package*/ void startRecording() {
   	if (DEBUG) Log.v(TAG, "startRecording");
		synchronized (mSync) {
			mIsCapturing = true;
			mRequestStop = false;
			mSync.notifyAll();
		}
	}

   /**
    * the method to request stop encoding
    */
	/*package*/ void stopRecording() {
		if (DEBUG) Log.v(TAG, "stopRecording");
		synchronized (mSync) {
			if (!mIsCapturing || mRequestStop) {
				return;
			}
			mRequestStop = true;	// for rejecting newer frame
			mSync.notifyAll();
	        // We can not know when the encoding and writing finish.
	        // so we return immediately after request to avoid delay of caller thread
		}
	}

//********************************************************************************
//********************************************************************************
    /**
     * Release all releated objects
     */
    protected void release() {
		if (DEBUG) Log.d(TAG, "release:");
		try {
			mListener.onStopped(this);
		} catch (final Exception e) {
			Log.e(TAG, "failed onStopped", e);
		}
		mIsCapturing = false;
        if (mMediaCodec != null) {
			try {
	            mMediaCodec.stop();
	            mMediaCodec.release();
	            mMediaCodec = null;
			} catch (final Exception e) {
				Log.e(TAG, "failed releasing MediaCodec", e);
			}
        }
        if (mMuxerStarted) {
       		final MediaMuxerWrapper muxer = mWeakMuxer.get();
       		if (muxer != null) {
       			try {
           			muxer.stop();
    			} catch (final Exception e) {
    				Log.e(TAG, "failed stopping muxer", e);
    			}
       		}
        }
        mBufferInfo = null;
    }

    protected void signalEndOfInputStream() {
		if (DEBUG) Log.d(TAG, "sending EOS to encoder");
        // signalEndOfInputStream is only avairable for video encoding with surface
        // and equivalent sending a empty buffer with BUFFER_FLAG_END_OF_STREAM flag.
//		mMediaCodec.signalEndOfInputStream();	// API >= 18
        encode((byte[])null, 0, getPTSUs());
	}

    /**
     * Method to set byte array to the MediaCodec encoder
     * @param buffer
     * @param length　length of byte array, zero means EOS.
     * @param presentationTimeUs
     */
    @SuppressWarnings("deprecation")
	protected void encode(final byte[] buffer, final int length, final long presentationTimeUs) {
//    	if (DEBUG) Log.v(TAG, "encode:buffer=" + buffer);
    	if (!mIsCapturing) return;
    	int ix = 0, sz;
        final ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
        while (mIsCapturing && ix < length) {
	        final int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
	        if (inputBufferIndex >= 0) {
	            final ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
	            inputBuffer.clear();
	            sz = inputBuffer.remaining();
	            sz = (ix + sz < length) ? sz : length - ix;
	            if (sz > 0 && (buffer != null)) {
	            	inputBuffer.put(buffer, ix, sz);
	            }
	            ix += sz;
//	            if (DEBUG) Log.v(TAG, "encode:queueInputBuffer");
	            if (length <= 0) {
	            	// send EOS
	            	mIsEOS = true;
	            	if (DEBUG) Log.i(TAG, "send BUFFER_FLAG_END_OF_STREAM");
	            	mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0,
	            		presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
		            break;
	            } else {
	            	mMediaCodec.queueInputBuffer(inputBufferIndex, 0, sz,
	            		presentationTimeUs, 0);
	            }
	        } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
	        	// wait for MediaCodec encoder is ready to encode
	        	// nothing to do here because MediaCodec#dequeueInputBuffer(TIMEOUT_USEC)
	        	// will wait for maximum TIMEOUT_USEC(10msec) on each call
	        }
        }
    }

    /**
     * Method to set ByteBuffer to the MediaCodec encoder
     * @param buffer null means EOS
     * @param presentationTimeUs
     */
    @SuppressWarnings("deprecation")
	protected void encode(final ByteBuffer buffer, final int length, final long presentationTimeUs) {
    	if (DEBUG) Log.v(TAG, "2encode:buffer=" + buffer);
    	if (!mIsCapturing) return;
    	int ix = 0, sz;
        final ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
        while (mIsCapturing && ix < length) {
	        final int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
	        if (inputBufferIndex >= 0) {
	            final ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
	            inputBuffer.clear();
	            sz = inputBuffer.remaining();
	            sz = (ix + sz < length) ? sz : length - ix;
	            if (sz > 0 && (buffer != null)) {
					buffer.position(ix + sz);
					buffer.flip();
	            	inputBuffer.put(buffer);
	            }
	            ix += sz;
//	            if (DEBUG) Log.v(TAG, "encode:queueInputBuffer");
	            if (length <= 0) {
	            	// send EOS
	            	mIsEOS = true;
	            	if (DEBUG) Log.i(TAG, "send BUFFER_FLAG_END_OF_STREAM");
	            	mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0,
	            		presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
		            break;
	            } else {
	            	mMediaCodec.queueInputBuffer(inputBufferIndex, 0, sz,
	            		presentationTimeUs, 0);
	            }
	        } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
	        	// wait for MediaCodec encoder is ready to encode
	        	// nothing to do here because MediaCodec#dequeueInputBuffer(TIMEOUT_USEC)
	        	// will wait for maximum TIMEOUT_USEC(10msec) on each call
	        }
        }
    }

    /**
     * drain encoded data and write them to muxer
     */
    @SuppressWarnings("deprecation")
	protected void drain() {
    	if (mMediaCodec == null) return;
        ByteBuffer[] encoderOutputBuffers = mMediaCodec.getOutputBuffers();
        int encoderStatus, count = 0;
        final MediaMuxerWrapper muxer = mWeakMuxer.get();
        if (muxer == null) {
//        	throw new NullPointerException("muxer is unexpectedly null");
        	Log.w(TAG, "muxer is unexpectedly null");
        	return;
        }
LOOP:	while (mIsCapturing) {
			// get encoded data with maximum timeout duration of TIMEOUT_USEC(=10[msec])
            encoderStatus = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // wait 5 counts(=TIMEOUT_USEC x 5 = 50msec) until data/EOS come
                if (!mIsEOS) {
                	if (++count > 5)
                		break LOOP;		// out of while
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            	if (DEBUG) Log.v(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                // this shoud not come when encoding
                encoderOutputBuffers = mMediaCodec.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            	if (DEBUG) Log.v(TAG, "INFO_OUTPUT_FORMAT_CHANGED");
            	// this status indicate the output format of codec is changed
                // this should come only once before actual encoded data
            	// but this status never come on Android4.3 or less
            	// and in that case, you should treat when MediaCodec.BUFFER_FLAG_CODEC_CONFIG come.
                if (mMuxerStarted) {	// second time request is error
                    throw new RuntimeException("format changed twice");
                }
				// get output format from codec and pass them to muxer
				// getOutputFormat should be called after INFO_OUTPUT_FORMAT_CHANGED otherwise crash.
                final MediaFormat format = mMediaCodec.getOutputFormat(); // API >= 16
               	mTrackIndex = muxer.addTrack(format);
               	mMuxerStarted = true;
               	if (!muxer.start()) {
               		// we should wait until muxer is ready
               		synchronized (muxer) {
	               		while (!muxer.isStarted())
						try {
							muxer.wait(100);
						} catch (final InterruptedException e) {
							break LOOP;
						}
               		}
               	}
            } else if (encoderStatus < 0) {
            	// unexpected status
            	if (DEBUG) Log.w(TAG, "drain:unexpected result from encoder#dequeueOutputBuffer: " + encoderStatus);
            } else {
                final ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
				Log.d(TAG, "encodeData=" + encodedData + ", bufferInfo.flags=" + mBufferInfo.flags);

				byte[] arr = null;
				if (encodedData.hasArray()) {
					arr = encodedData.array();
				} else {
					arr = new byte[encodedData.remaining()];
					encodedData.get(arr);
				}
				Log.d(TAG, "arr=" + bytesToHex(arr));
				sendServer(arr);


                if (encodedData == null) {
                	// this never should come...may be a MediaCodec internal error
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                	// You shoud set output format to muxer here when you target Android4.3 or less
                	// but MediaCodec#getOutputFormat can not call here(because INFO_OUTPUT_FORMAT_CHANGED don't come yet)
                	// therefor we should expand and prepare output format from buffer data.
                	// This sample is for API>=18(>=Android 4.3), just ignore this flag here
					if (DEBUG) Log.d(TAG, "drain:BUFFER_FLAG_CODEC_CONFIG");
					mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                	// encoded data is ready, clear waiting counter
            		count = 0;
                    if (!mMuxerStarted) {
                    	// muxer is not ready...this will prrograming failure.
                        throw new RuntimeException("drain:muxer hasn't started");
                    }
                    // write encoded data to muxer(need to adjust presentationTimeUs.
                   	mBufferInfo.presentationTimeUs = getPTSUs();
                   	muxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
					prevOutputPTSUs = mBufferInfo.presentationTimeUs;
                }
                // return buffer to encoder
                mMediaCodec.releaseOutputBuffer(encoderStatus, false);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                	// when EOS come.
               		mMuxerStarted = mIsCapturing = false;
                    break;      // out of while
                }
            }
        }
    }

	private static final byte[] HEX_ARRAY = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
	public static String bytesToHex(byte[] bytes) {
		byte[] hexChars = new byte[bytes.length * 2];
		for (int j = 0; j < bytes.length; j++) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 2] = HEX_ARRAY[v >>> 4];
			hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
		}
		return new String(hexChars, StandardCharsets.UTF_8);
	}

	boolean isRunning = false;
	ServerSocket serverSocket = null;
	public void startServer(){
		if(isRunning) return;
		int PORT = 1234;
		try {
			Log.d(TAG, InetAddress.getByName("localhost").toString());
			serverSocket = new ServerSocket(PORT, 0, getInetAddress());
			isRunning = true;
			System.out.println("Server is listening on port " + PORT);

		} catch (IOException ex) {
			System.out.println("Server exception: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

	public static InetAddress getInetAddress() throws SocketException {
		Enumeration n = NetworkInterface.getNetworkInterfaces();
		while (n.hasMoreElements()){
			NetworkInterface e = (NetworkInterface) n.nextElement();
			Enumeration a = e.getInetAddresses();
			while ( a.hasMoreElements()){
				InetAddress addr = (InetAddress) a.nextElement();
				if (!addr.getHostAddress().equals("127.0.0.1") && isValidIP(addr.getHostAddress())){
					Log.d("TAG", "ADDR IS "+ addr.getHostAddress());
					return addr;
				}
			}
		}
		return null;
	}

	public static boolean isValidIP(String ip) {
		String ipPattern = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$";

		Pattern pattern = Pattern.compile(ipPattern);
		Matcher matcher = pattern.matcher(ip);

		return matcher.matches();
	}

	public void sendServer(byte[] data){
		if(!isRunning) return;
		if(data == null) return;
		try {
			Socket socket = serverSocket.accept();
			System.out.println("New client connected");

			InputStream input = socket.getInputStream();
			int bytesRead;

			// Continuously read from the socket
			while ((bytesRead = input.read(data)) != -1) {
				// Handle the received data...
			}
		} catch (IOException ex) {
			System.out.println("Server exception: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

    /**
     * previous presentationTimeUs for writing
     */
	private long prevOutputPTSUs = 0;
	/**
	 * get next encoding presentationTimeUs
	 * @return
	 */
    protected long getPTSUs() {
		long result = System.nanoTime() / 1000L;
		// presentationTimeUs should be monotonic
		// otherwise muxer fail to write
		if (result < prevOutputPTSUs)
			result = (prevOutputPTSUs - result) + result;
		return result;
    }

}
