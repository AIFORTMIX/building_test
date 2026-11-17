package com.kernelai.app.driver;

import android.os.SharedMemory;

interface IRootDriver {
    boolean open();
    void close();
    String getLastError();
    int ioctl(int command, in SharedMemory memory, int size);
}
