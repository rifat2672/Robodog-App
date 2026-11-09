package com.rifat.robodog;

/**
 * ফোন আর ESP32 এর মধ্যে সংযোগ — WiFi (WifiLink) বা Bluetooth (BleLink)।
 * দুটোই একই text কমান্ড পাঠায়: "F", "S", "SPD:5" ইত্যাদি (প্রতিটা আলাদা লাইনে)।
 * Listener এর সব callback main thread এ আসে।
 */
public interface DogLink {

    interface Listener {
        /** সংযোগ তৈরি, এখন কমান্ড পাঠানো যাবে */
        void onConnected(DogLink link, String info);

        /** সংযোগ ব্যর্থ বা কেটে গেছে (নিজে close() করলে এটা আসে না) */
        void onDisconnected(DogLink link, String reason);

        /** ESP32 থেকে আসা এক লাইন, যেমন "ST:5,30,0,0,0,0,0,0,0,0,0" */
        void onLine(DogLink link, String line);
    }

    void connect();

    void send(String command);

    void close();

    boolean isConnected();
}
