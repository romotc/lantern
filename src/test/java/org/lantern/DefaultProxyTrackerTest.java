package org.lantern;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.netty.util.Timer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;

import org.junit.Test;
import org.lantern.event.Events;
import org.lantern.event.ProxyConnectionEvent;
import org.lantern.state.Model;
import org.lantern.stubs.PeerFactoryStub;
import org.littleshoot.util.FiveTuple;

import com.google.common.eventbus.Subscribe;

public class DefaultProxyTrackerTest {
    
    @Subscribe
    public void onProxyConnectionEvent(final ProxyConnectionEvent pce) {
        synchronized (this) {
            this.notifyAll();
        }
    }
    
    @Test
    public void testDefaultProxyTracker() throws Exception {
        
        Events.register(this);
        final Censored censored = new DefaultCensored();
        final CountryService countryService = new CountryService(censored);
        Model model = new Model(countryService);

        //assume that we are connected to the Internet
        model.getConnectivity().setInternet(true);

        PeerFactory peerFactory = new PeerFactoryStub();
        Timer timer = mock(Timer.class);
        DefaultXmppHandler xmppHandler = mock(DefaultXmppHandler.class);
        LanternTrustStore lanternTrustStore = mock(LanternTrustStore.class);
        DefaultProxyTracker tracker = new DefaultProxyTracker(model,
                peerFactory, xmppHandler, lanternTrustStore);

        //proxy queue initially empty
        ProxyHolder proxy = tracker.firstConnectedProxy();
        assertNull(proxy);

        Miniproxy miniproxy1 = new Miniproxy(55021);
        new Thread(miniproxy1).start();
        LanternUtils.waitForServer(miniproxy1.port, 4000);

        Miniproxy miniproxy2 = new Miniproxy(55022);
        new Thread(miniproxy2).start();
        LanternUtils.waitForServer(miniproxy2.port, 4000);

        InetAddress localhost = org.littleshoot.proxy.impl.NetworkUtils.getLocalHost();
        tracker.addProxy(new URI("proxy1@example.com"), new InetSocketAddress(localhost, 55021));
        proxy = waitForProxy(tracker);
        
        assertEquals(55021, getProxyPort(proxy));
        assertEquals(0, proxy.getFailures());

        //now let's force the proxy to fail.
        //miniproxy1.pause();

        proxy = tracker.firstConnectedProxy();
        // first, we need to clear out the old proxy from the list, by having it
        // fail.
        tracker.onCouldNotConnect(proxy);
        //now wait for the miniproxy to stop accepting.
        Thread.sleep(10);

        proxy = tracker.firstConnectedProxy();
        assertNull(proxy);

        // now bring miniproxy1 back up
        // miniproxy1.unpause();
        Thread.sleep(10);

        //let's turn off internet, which will restore the dead proxy
        model.getConnectivity().setInternet(false);
        Events.eventBus().post(new ConnectivityChangedEvent(true, false, null));
        Thread.sleep(10);

        proxy = tracker.firstConnectedProxy();
        assertNotNull("Recently deceased proxy not restored", proxy);
        Thread.sleep(10);
        model.getConnectivity().setInternet(true);
        Events.eventBus().post(new ConnectivityChangedEvent(true, false, null));
        tracker.firstConnectedProxy();
        Thread.sleep(10);

        // with multiple proxies, we get a different proxy for each getProxy()
        // call
        tracker.addProxy(new URI("proxy2@example.com"), new InetSocketAddress(localhost, 55022));
        Thread.sleep(100);
        ProxyHolder proxy1 = waitForProxy(tracker);
        System.err.println(proxy1);
        // Simulate a successful connection to proxy1 to bump its socket count 
        proxy1.connectionSucceeded();
        ProxyHolder proxy2 = waitForProxy(tracker);
        System.err.println(proxy2);
        assertNotNull(proxy1);
        assertNotNull(proxy2);
        assertTrue(proxy1 != proxy2);
        int port1 = getProxyPort(proxy1);
        int port2 = getProxyPort(proxy2);
        assertTrue((port1 == 55021 && port2 == 55022) || (port1 == 55022 && port2 == 55021));

    }

    private ProxyHolder waitForProxy(DefaultProxyTracker tracker) 
        throws Exception {
        synchronized (this) {
            final ProxyHolder proxy = tracker.firstConnectedProxy();
            if (proxy != null) {
                return proxy;
            }
            this.wait(6000);
            return tracker.firstConnectedProxy();
        }
    }

    private int getProxyPort(ProxyHolder proxy) {
        final FiveTuple ft = proxy.getFiveTuple();
        final InetSocketAddress remote = ft.getRemote();
        return remote.getPort();
    }

    static class Miniproxy implements Runnable {

        public volatile boolean done = false;
        private final int port;
        private boolean paused;

        public Miniproxy(int port) {
            this.port = port;
        }

        public void unpause() {
            paused = false;
        }

        public void pause() {
            paused = true;
        }

        @Override
        public void run() {
            ServerSocket sock;
            try {
                sock = new ServerSocket(port);
                sock.setSoTimeout(1);
                while (!done) {
                    try {
                        if (!paused) {
                            sock.accept();
                        }
                    } catch (SocketTimeoutException e) {
                        // no connections; just loop
                    }

                    try {
                        Thread.sleep(0);
                    } catch (InterruptedException e) {
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }
}
