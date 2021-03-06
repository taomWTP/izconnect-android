package org.taom.android.alljoyn;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.BoolRes;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;

import org.alljoyn.bus.BusException;
import org.alljoyn.bus.ProxyBusObject;
import org.alljoyn.bus.Variant;
import org.taom.android.NotificationService;
import org.taom.android.R;
import org.taom.android.devices.DeviceAdapter;
import org.taom.android.devices.DeviceAdapterItem;
import org.taom.android.tabs.fragments.ControlsFragment;
import org.taom.izconnect.network.interfaces.BoardInterface;
import org.taom.izconnect.network.interfaces.MobileInterface;
import org.taom.izconnect.network.interfaces.PCInterface;

import java.io.File;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import static org.taom.izconnect.network.GFLogger.*;

public class AllJoynService extends Service {
    private static final String TAG = "AllJoynService";
    private static final int NOTIFICATION_ID = 0xdefaced;
    private static final int MAX_BYTES = 114500;

    private AndroidNetworkService mNetworkService;
    private MobileServiceImpl mobileService;
    private BackgroundHandler mBackgroundHandler;
    private Set<String> subscribers = Collections.synchronizedSet(new HashSet<String>());

    private DeviceAdapter deviceAdapter;
    private Map<DeviceAdapterItem, ProxyBusObject> map = new ConcurrentHashMap<>();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new AllJoynBinder();
    }

    public void onCreate() {
        HandlerThread busThread = new HandlerThread("BackgroundHandler");
        busThread.start();
        mBackgroundHandler = new BackgroundHandler(busThread.getLooper());

        startForeground(NOTIFICATION_ID, createNotification());

        mobileService = new MobileServiceImpl(subscribers);
        mNetworkService = new AndroidNetworkService(mBackgroundHandler);

        mBackgroundHandler.connect();
        mBackgroundHandler.registerInterface();
        mBackgroundHandler.announce();
        mBackgroundHandler.registerListeners();
    }

    private Notification createNotification() {
        CharSequence title = "IZConnect";
        CharSequence message = "Alljoyn bus service.";

        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.logo)
                .setContentTitle(title)
                .setContentText(message);
        return mBuilder.build();
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public final class BackgroundHandler extends Handler {
        public BackgroundHandler(Looper looper) {
            super(looper);
        }

        public void connect() {
            Message msg = mBackgroundHandler.obtainMessage(CONNECT);
            mBackgroundHandler.sendMessage(msg);
        }

        public void disconnect() {
            Message msg = mBackgroundHandler.obtainMessage(DISCONNECT);
            mBackgroundHandler.sendMessage(msg);
        }

        public void registerInterface() {
            Message msg = mBackgroundHandler.obtainMessage(REGISTER_INTERFACE);
            mBackgroundHandler.sendMessage(msg);
        }

        public void unregisterInterface() {
            Message msg = mBackgroundHandler.obtainMessage(UNREGISTER_INTERFACE);
            mBackgroundHandler.sendMessage(msg);
        }

        public void announce() {
            Message msg = mBackgroundHandler.obtainMessage(ANNOUNCE);
            mBackgroundHandler.sendMessage(msg);
        }

        public void registerListeners() {
            Message msg = mBackgroundHandler.obtainMessage(REGISTER_LISTENERS);
            mBackgroundHandler.sendMessage(msg);
        }

        public void unregisterListeners() {
            Message msg = mBackgroundHandler.obtainMessage(UNREGISTER_LISTENERS);
            mBackgroundHandler.sendMessage(msg);
        }

        public void deviceDiscovered(int id, ProxyBusObject proxyBusObject) {
            Message msg = mBackgroundHandler.obtainMessage(DEVICE_DISCOVERED, id, 0, proxyBusObject);
            mBackgroundHandler.sendMessage(msg);
        }

        public void deviceLost(ProxyBusObject proxyBusObject) {
            Message msg = mBackgroundHandler.obtainMessage(DEVICE_LOST, proxyBusObject);
            mBackgroundHandler.sendMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CONNECT:
                    mNetworkService.doConnect();
                    break;
                case DISCONNECT:
                    mNetworkService.doDisconnect();
                    getLooper().quit();
                    break;
                case REGISTER_INTERFACE:
                    mNetworkService.registerInterface(mobileService);
                    break;
                case UNREGISTER_INTERFACE:
                    if (mobileService != null) {
                        mNetworkService.unregisterInterface(mobileService);
                    }
                    break;
                case ANNOUNCE:
                    mNetworkService.announce();
                    break;
                case REGISTER_LISTENERS:
                    mNetworkService.registerListeners();
                    break;
                case UNREGISTER_LISTENERS:
                    mNetworkService.unregisterListeners();
                    break;
                case DEVICE_DISCOVERED:
                    doDeviceDiscovered(msg.arg1, (ProxyBusObject)msg.obj);
                    break;
                case DEVICE_LOST:
                    doDeviceLost((ProxyBusObject)msg.obj);
                    break;

                case NotificationService.NOTIFY_SUBSCRIBERS:
                    Iterator<String> it = subscribers.iterator();
                    while (it.hasNext()) {
                        String busName = it.next();
                        DeviceAdapterItem device = deviceAdapter.findByBusName(busName);
                        ProxyBusObject proxyBusObject = map.get(device);
                        if (proxyBusObject == null) {
                            it.remove();
                        }
                        String[] titleAndText = ((String) msg.obj).split(":");
                        switch (device.getDeviceType()) {
                            case PC:
                                PCInterface pcInterface = proxyBusObject.getInterface(PCInterface.class);
                                try {
                                    pcInterface.notify(mobileService.getDeviceName(), titleAndText[0].trim(), titleAndText[1].trim());
                                } catch (BusException e) {
                                    log(Level.SEVERE, TAG, "Cannot send notification");
                                }
                                break;
                            case MOBILE:
                                MobileInterface mobileInterface = proxyBusObject.getInterface(MobileInterface.class);
                                try {
                                    mobileInterface.notify(mobileService.getDeviceName(), titleAndText[0].trim(), titleAndText[1].trim());
                                } catch (BusException e) {
                                    log(Level.SEVERE, TAG, "Cannot send notification");
                                }
                                break;
                        }
                    }
                    break;

                default:
                    switch (deviceAdapter.getSelectedItem().getDeviceType()) {
                        case BOARD:
                            processBoardMessage(msg);
                            break;
                        case MOBILE:
                            processMobileMessage(msg);
                            break;
                        case PC:
                            processPCMessage(msg);
                            break;
                    }
                    break;
            }
        }
    }

    private static final int CONNECT = 1;
    private static final int DISCONNECT = 2;
    private static final int REGISTER_INTERFACE = 3;
    private static final int UNREGISTER_INTERFACE = 4;
    private static final int ANNOUNCE = 5;
    private static final int REGISTER_LISTENERS = 6;
    private static final int UNREGISTER_LISTENERS = 7;
    private static final int DEVICE_DISCOVERED = 8;
    private static final int DEVICE_LOST = 9;

    private void processPCMessage(Message msg) {
        PCInterface pcInterface = getCurrentProxyObj().getInterface(PCInterface.class);
        boolean isScript = false;
        switch (msg.what) {
            case ControlsFragment.VOLUME_CHANGED:
                try {
                    pcInterface.setVolume(msg.arg1);
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot change volume");
                }
                break;

            case ControlsFragment.MEDIA_PLAY_BUTTON:
                try {
                    pcInterface.mediaControlPlayPause();
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send media control signal");
                }
                break;

            case ControlsFragment.MEDIA_STOP_BUTTON:
                try {
                    pcInterface.mediaControlStop();
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send media control signal");
                }
                break;

            case ControlsFragment.MEDIA_NEXT_BUTTON:
                try {
                    pcInterface.mediaControlNext();
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send media control signal");
                }
                break;

            case ControlsFragment.MEDIA_PREVIOUS_BUTTON:
                try {
                    pcInterface.mediaControlPrevious();
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send media control signal");
                }
                break;

            case ControlsFragment.MOUSE_MOVE:
                try {
                    pcInterface.mouseMove(msg.arg1, msg.arg2);
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send media control signal");
                }
                break;

            case ControlsFragment.MOUSE_LEFT_CLICK:
                try {
                    pcInterface.mouseLeftClick();
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send media control signal");
                }
                break;

            case ControlsFragment.MOUSE_RIGHT_CLICK:
                try {
                    pcInterface.mouseRightClick();
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send media control signal");
                }
                break;

            case ControlsFragment.KEY_PRESSED:
                try {
                    pcInterface.keyPressed(msg.arg1);
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send media control signal");
                }
                break;

            case ControlsFragment.SLIDESHOW_START:
                try {
                    pcInterface.slideshowStart();
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send media control signal");
                }
                break;

            case ControlsFragment.SLIDESHOW_STOP:
                try {
                    pcInterface.slideshowStop();
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send media control signal");
                }
                break;

            case ControlsFragment.NEXT_SLIDE:
                try {
                    pcInterface.nextSlide();
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send media control signal");
                }
                break;

            case ControlsFragment.PREV_SLIDE:
                try {
                    pcInterface.previousSlide();
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send media control signal");
                }
                break;

            case ControlsFragment.SCRIPT_ADD:
                isScript = true;
            case ControlsFragment.FILE_SEND:

                File file = new File((String) msg.obj);
                if (!file.exists())
                    break;

                try {
                    FileInputStream in = new FileInputStream(file);
                    int length = (int) file.length();
                    byte[] tempBytes;
                    int numRead = 0;
                    int numChunks = length / MAX_BYTES + (length % MAX_BYTES == 0 ? 0 : 1);
                    int maxProgress = numChunks;
                    for (int i = 0; i < numChunks; i++) {
                        tempBytes = null;
                        int offset = 0;
                        numRead = 0;
                        if (MAX_BYTES > length) {
                            tempBytes = new byte[length];
                        } else {
                            tempBytes = new byte[MAX_BYTES];
                        }
                        while (offset < tempBytes.length && (numRead = in.read(tempBytes, 0, tempBytes.length - offset)) >= 0) {
                            offset += numRead;
                        }
                        length -= MAX_BYTES;
                        pcInterface.fileData(file.getName(), tempBytes, isScript);
                    }
                    in.close();
                    pcInterface.fileData(file.getName(), new byte[0], isScript);
                } catch (Exception e) {
                    log(Level.SEVERE, TAG, "Cannot send file");
                } finally {
                    Intent i = new Intent(ControlsFragment.STATUS_BROADCAST_ACTION);
                    sendBroadcast(i);
                }

                break;

            case ControlsFragment.SCRIPT_RUN:
                try {
                    pcInterface.runScript((String)msg.obj);
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot start script");
                }
                break;
        }

    }

    private void processMobileMessage(Message msg) {
        boolean isScript = false;
        MobileInterface mobileInterface = getCurrentProxyObj().getInterface(MobileInterface.class);
        switch (msg.what) {
            case ControlsFragment.SCRIPT_ADD:
                isScript = true;
            case ControlsFragment.FILE_SEND:

                File file = new File((String) msg.obj);
                if (!file.exists())
                    break;

                try {
                    FileInputStream in = new FileInputStream(file);
                    int length = (int) file.length();
                    byte[] tempBytes;
                    int numRead = 0;
                    int numChunks = length / MAX_BYTES + (length % MAX_BYTES == 0 ? 0 : 1);
                    int maxProgress = numChunks;
                    for (int i = 0; i < numChunks; i++) {
                        tempBytes = null;
                        int offset = 0;
                        numRead = 0;
                        if (MAX_BYTES > length) {
                            tempBytes = new byte[length];
                        } else {
                            tempBytes = new byte[MAX_BYTES];
                        }
                        while (offset < tempBytes.length && (numRead = in.read(tempBytes, 0, tempBytes.length - offset)) >= 0) {
                            offset += numRead;
                        }
                        length -= MAX_BYTES;
                        mobileInterface.fileData(file.getName(), tempBytes, isScript);
                    }
                    in.close();
                    mobileInterface.fileData(file.getName(), new byte[0], isScript);
                } catch (Exception e) {
                    log(Level.SEVERE, TAG, "Cannot send file");
                } finally {
                    Intent i = new Intent(ControlsFragment.STATUS_BROADCAST_ACTION);
                    sendBroadcast(i);
                }

                break;

            case ControlsFragment.SCRIPT_RUN:
                try {
                    mobileInterface.runScript((String)msg.obj);
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot start script");
                }
                break;
        }
    }

    private void processBoardMessage(Message msg) {
        BoardInterface boardInterface = getCurrentProxyObj().getInterface(BoardInterface.class);
        switch (msg.what) {
            case ControlsFragment.LIGHT_TOGGLE:
                try {
                    boardInterface.setLight((Boolean) msg.obj);
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send turn on signal");
                }
                break;

            case ControlsFragment.AUTO_MODE_TOGGLE:
                try {
                    boardInterface.setAutoMode((Boolean) msg.obj);
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot send auto mode signal");
                }
                break;

            case ControlsFragment.SCRIPT_ADD:

                File file = new File((String) msg.obj);
                if (!file.exists())
                    break;

                try {
                    FileInputStream in = new FileInputStream(file);
                    int length = (int) file.length();
                    byte[] tempBytes;
                    int numRead = 0;
                    int numChunks = length / MAX_BYTES + (length % MAX_BYTES == 0 ? 0 : 1);
                    int maxProgress = numChunks;
                    for (int i = 0; i < numChunks; i++) {
                        tempBytes = null;
                        int offset = 0;
                        numRead = 0;
                        if (MAX_BYTES > length) {
                            tempBytes = new byte[length];
                        } else {
                            tempBytes = new byte[MAX_BYTES];
                        }
                        while (offset < tempBytes.length && (numRead = in.read(tempBytes, 0, tempBytes.length - offset)) >= 0) {
                            offset += numRead;
                        }
                        length -= MAX_BYTES;
                        boardInterface.fileData(file.getName(), tempBytes, true);
                    }
                    in.close();
                    boardInterface.fileData(file.getName(), new byte[0], true);
                } catch (Exception e) {
                    log(Level.SEVERE, TAG, "Cannot send file");
                } finally {
                    Intent i = new Intent(ControlsFragment.STATUS_BROADCAST_ACTION);
                    sendBroadcast(i);
                }

                break;

            case ControlsFragment.SCRIPT_RUN:
                try {
                    boardInterface.runScript((String)msg.obj);
                } catch (BusException e) {
                    log(Level.SEVERE, TAG, "Cannot start script");
                }
                break;
        }
    }

    private ProxyBusObject getCurrentProxyObj() {
        return map.get(deviceAdapter.getSelectedItem());
    }

    private void doDeviceDiscovered(int id, ProxyBusObject proxyBusObject) {
        String busName = proxyBusObject.getBusName();
        if (busName.equals(mNetworkService.getBusName()))
            return;

        DeviceAdapterItem.DeviceType deviceType = DeviceAdapterItem.DeviceType.valueOf(id);

        String deviceName;
        String deviceOS;
        try {
            Map<String, Variant> properties = proxyBusObject.getAllProperties(deviceType.getInterfaceClass());
            deviceName = properties.get("DeviceName").getObject(String.class);
            deviceOS = properties.get("DeviceOS").getObject(String.class);

        } catch (BusException e) {
            log(Level.SEVERE, TAG, "Cannot get device info.");
            deviceName = "UNKNOWN";
            deviceOS = "UNKNOWN";
        }

        DeviceAdapterItem deviceAdapterItem = new DeviceAdapterItem(busName, deviceType, deviceName, deviceOS);

        if (deviceAdapter != null) {
            deviceAdapter.add(deviceAdapterItem);
        }
        map.put(deviceAdapterItem, proxyBusObject);
    }

    private void doDeviceLost(ProxyBusObject device) {
        for (Map.Entry<DeviceAdapterItem, ProxyBusObject> entry : map.entrySet()) {
            if (device == entry.getValue()) {
                if (deviceAdapter != null) {
                    deviceAdapter.remove(entry.getKey());
                }
                map.remove(entry.getKey());
                return;
            }
        }
    }

    @Override
    public void onDestroy() {
        mBackgroundHandler.unregisterListeners();
        mBackgroundHandler.unregisterInterface();
        mBackgroundHandler.disconnect();
    }

    public void setDeviceAdapter(DeviceAdapter deviceAdapter) {
        if (this.deviceAdapter != deviceAdapter) {
            deviceAdapter.addAll(map.keySet());
            this.deviceAdapter = deviceAdapter;
        }
    }

    public class AllJoynBinder extends Binder {
        public AllJoynService getInstance() {
            return AllJoynService.this;
        }

        public Handler getHandler() {
            return mBackgroundHandler;
        }
    }
}
