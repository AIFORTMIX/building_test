# qingwei_mcp Driver - External Module Makefile
# Usage: make KDIR=/path/to/kernel/source [ARCH=arm64] [CROSS_COMPILE=aarch64-linux-gnu-]

# Kleaf supplies KERNEL_SRC and command-line O/M/LLVM tool arguments.
KDIR ?= $(KERNEL_SRC)
KERNEL_SRC ?= /lib/modules/$(shell uname -r)/build
ARCH ?= arm64
CROSS_COMPILE ?= aarch64-linux-gnu-

M ?= $(CURDIR)

# Default target
all: modules

modules modules_install:
	$(MAKE) -C "$(KDIR)" M="$(M)" ARCH=$(ARCH) CROSS_COMPILE=$(CROSS_COMPILE) $@

clean:
	$(MAKE) -C "$(KDIR)" M="$(M)" clean

# For standalone Makefile auto-generation (used by ABK-KO build system)
.PHONY: all modules modules_install clean
