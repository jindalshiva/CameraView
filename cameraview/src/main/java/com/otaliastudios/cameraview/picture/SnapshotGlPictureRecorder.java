package com.otaliastudios.cameraview.picture;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.Matrix;
import android.os.Build;

import com.otaliastudios.cameraview.CameraLogger;
import com.otaliastudios.cameraview.PictureResult;
import com.otaliastudios.cameraview.overlay.SurfaceDrawer;
import com.otaliastudios.cameraview.controls.Facing;
import com.otaliastudios.cameraview.engine.CameraEngine;
import com.otaliastudios.cameraview.engine.offset.Axis;
import com.otaliastudios.cameraview.engine.offset.Reference;
import com.otaliastudios.cameraview.internal.egl.EglCore;
import com.otaliastudios.cameraview.internal.egl.EglViewport;
import com.otaliastudios.cameraview.internal.egl.EglWindowSurface;
import com.otaliastudios.cameraview.internal.utils.CropHelper;
import com.otaliastudios.cameraview.internal.utils.WorkerHandler;
import com.otaliastudios.cameraview.preview.GlCameraPreview;
import com.otaliastudios.cameraview.preview.RendererFrameCallback;
import com.otaliastudios.cameraview.preview.RendererThread;
import com.otaliastudios.cameraview.size.AspectRatio;
import com.otaliastudios.cameraview.size.Size;

import androidx.annotation.NonNull;

import android.view.Surface;

import java.util.List;

public class SnapshotGlPictureRecorder extends PictureRecorder {

    private static final String TAG = SnapshotGlPictureRecorder.class.getSimpleName();
    private static final CameraLogger LOG = CameraLogger.create(TAG);

    private CameraEngine mEngine;
    private GlCameraPreview mPreview;
    private AspectRatio mOutputRatio;

    private List<SurfaceDrawer> mSurfaceDrawerList;
    private boolean mWithOverlay;

    public SnapshotGlPictureRecorder(
            @NonNull PictureResult.Stub stub,
            @NonNull CameraEngine engine,
            @NonNull GlCameraPreview preview,
            @NonNull AspectRatio outputRatio,
            @NonNull List<SurfaceDrawer> surfaceDrawerList) {
        super(stub, engine);
        mEngine = engine;
        mPreview = preview;
        mOutputRatio = outputRatio;
        mWithOverlay = true;
        mSurfaceDrawerList = surfaceDrawerList;
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    public void take() {
        mPreview.addRendererFrameCallback(new RendererFrameCallback() {

            int mTextureId;
            int mOverlayTextureId = 0;
            SurfaceTexture mSurfaceTexture;
            SurfaceTexture mOverlaySurfaceTexture;
            float[] mTransform;
            float[] mOverlayTransform;
            EglViewport viewport;

            @RendererThread
            public void onRendererTextureCreated(int textureId) {
                mTextureId = textureId;
                viewport = new EglViewport();
                if (mWithOverlay) {
                    mOverlayTextureId = viewport.createTexture();
                }
                mSurfaceTexture = new SurfaceTexture(mTextureId, true);
                if (mWithOverlay) {
                    mOverlaySurfaceTexture = new SurfaceTexture(mOverlayTextureId, true);
                }
                // Need to crop the size.
                Rect crop = CropHelper.computeCrop(mResult.size, mOutputRatio);
                mResult.size = new Size(crop.width(), crop.height());
                mSurfaceTexture.setDefaultBufferSize(mResult.size.getWidth(), mResult.size.getHeight());
                if (mWithOverlay) {
                    mOverlaySurfaceTexture.setDefaultBufferSize(mResult.size.getWidth(), mResult.size.getHeight());
                }
                mTransform = new float[16];
                if (mWithOverlay) {
                    mOverlayTransform = new float[16];
                }
            }

            @RendererThread
            @Override
            public void onRendererFrame(@NonNull SurfaceTexture surfaceTexture, final float scaleX, final float scaleY) {
                mPreview.removeRendererFrameCallback(this);

                // This kinda work but has drawbacks:
                // - output is upside down due to coordinates in GL: need to flip the byte[] someway
                // - output is not rotated as we would like to: need to create a bitmap copy...
                // - works only in the renderer thread, where it allocates the buffer and reads pixels. Bad!
                /*
                ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
                buffer.rewind();
                ByteArrayOutputStream bos = new ByteArrayOutputStream(buffer.array().length);
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos);
                bitmap.recycle(); */

                // For this reason it is better to create a new surface,
                // and draw the last frame again there.
                final EGLContext eglContext = EGL14.eglGetCurrentContext();
                final EglCore core = new EglCore(eglContext, EglCore.FLAG_RECORDABLE);
                // final EGLSurface oldSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
                // final EGLDisplay oldDisplay = EGL14.eglGetCurrentDisplay();
                WorkerHandler.execute(new Runnable() {
                    @Override
                    public void run() {
                        EglWindowSurface surface = new EglWindowSurface(core, mSurfaceTexture);
                        surface.makeCurrent();
//                        EglViewport viewport = new EglViewport();
                        mSurfaceTexture.updateTexImage();
                        mSurfaceTexture.getTransformMatrix(mTransform);
                        Surface drawOnto = new Surface(mOverlaySurfaceTexture);
                        if (mWithOverlay) {
                            try {
                                final Canvas surfaceCanvas = drawOnto.lockCanvas(null);
                                surfaceCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                                for (SurfaceDrawer surfaceDrawer : mSurfaceDrawerList) {
                                    surfaceDrawer.drawOnSurfaceForPictureSnapshot(surfaceCanvas);
                                }

                                drawOnto.unlockCanvasAndPost(surfaceCanvas);
                            } catch (Surface.OutOfResourcesException e) {
                                e.printStackTrace();
                            }
                            mOverlaySurfaceTexture.updateTexImage();
                            mOverlaySurfaceTexture.getTransformMatrix(mOverlayTransform);
                        }

                        // Apply scale and crop:
                        // NOTE: scaleX and scaleY are in REF_VIEW, while our input appears to be in REF_SENSOR.
                        boolean flip = mEngine.getAngles().flip(Reference.VIEW, Reference.SENSOR);
                        float realScaleX = flip ? scaleY : scaleX;
                        float realScaleY = flip ? scaleX : scaleY;
                        float scaleTranslX = (1F - realScaleX) / 2F;
                        float scaleTranslY = (1F - realScaleY) / 2F;
                        Matrix.translateM(mTransform, 0, scaleTranslX, scaleTranslY, 0);
                        Matrix.scaleM(mTransform, 0, realScaleX, realScaleY, 1);

                        // Fix rotation:
                        // Not sure why we need the minus here... It makes no sense to me.
                        LOG.w("Recording frame. Rotation:", mResult.rotation, "Actual:", -mResult.rotation);
                        int rotation = -mResult.rotation;
                        int overlayRotation = mEngine.getAngles().offset(Reference.VIEW, Reference.OUTPUT, Axis.ABSOLUTE); // TODO check axis
                        // apparently with front facing camera with don't need the minus sign
                        if (mResult.facing == Facing.FRONT) {
                            overlayRotation = -overlayRotation;
                        }
                        mResult.rotation = 0;

                        // Go back to 0,0 so that rotate and flip work well.
                        Matrix.translateM(mTransform, 0, 0.5F, 0.5F, 0);
                        if (mOverlayTransform != null) {
                            Matrix.translateM(mOverlayTransform, 0, 0.5F, 0.5F, 0);
                        }

                        // Apply rotation:
                        Matrix.rotateM(mTransform, 0, rotation, 0, 0, 1);
                        if (mOverlayTransform != null) {
                            Matrix.rotateM(mOverlayTransform, 0, overlayRotation, 0, 0, 1);
                        }

                        // Flip horizontally for front camera:
                        if (mResult.facing == Facing.FRONT) {
                            Matrix.scaleM(mTransform, 0, -1, 1, 1);
                            if (mOverlayTransform != null) {
                                // not sure why we have to flip the mirror the y axis
                                Matrix.scaleM(mOverlayTransform, 0, -1, -1, 1);
                            }
                        }
                        if (mOverlayTransform != null) {
                            // not sure why we have to flip the mirror the y axis
                            Matrix.scaleM(mOverlayTransform, 0, 1, -1, 1);
                        }

                        // Go back to old position.
                        Matrix.translateM(mTransform, 0, -0.5F, -0.5F, 0);
                        if (mOverlayTransform != null) {
                            Matrix.translateM(mOverlayTransform, 0, -0.5F, -0.5F, 0);
                        }

                        // Future note: passing scale values to the viewport?
                        // They are simply realScaleX and realScaleY.
                        viewport.drawFrame(mTextureId, mTransform);
                        if (mWithOverlay) {
                            viewport.drawFrame(mOverlayTextureId, mOverlayTransform);
                        }
                        // don't - surface.swapBuffers();
                        mResult.data = surface.saveFrameTo(Bitmap.CompressFormat.JPEG);
                        mResult.format = PictureResult.FORMAT_JPEG;
                        mSurfaceTexture.releaseTexImage();

                        // EGL14.eglMakeCurrent(oldDisplay, oldSurface, oldSurface, eglContext);
                        surface.release();
                        viewport.release();
                        drawOnto.release();
                        mSurfaceTexture.release();
                        if (mOverlaySurfaceTexture != null) {
                            mOverlaySurfaceTexture.release();
                        }
                        core.release();
                        dispatchResult();
                    }
                });
            }
        });
    }

    @Override
    protected void dispatchResult() {
        mEngine = null;
        mOutputRatio = null;
        super.dispatchResult();
    }
}
