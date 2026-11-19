package com.kernelai.app.driver;

import android.os.SharedMemory;

interface IRootDriver {
    boolean open();
    void close();
    String getLastError();
    int ioctl(int command, in SharedMemory memory, int size);
    String processSnapshot(int pid);
    String processRecords(int pid, String kind, int offset, int limit, String expectedIdentity, String expectedRevision);
}
