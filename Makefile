KDIR  ?= /build/common
ARCH  ?= arm64
LLVM  ?= -19

obj-m += vcut_lower.o

all:
	$(MAKE) -C $(KDIR) M=$(CURDIR) ARCH=$(ARCH) LLVM=$(LLVM) modules

clean:
	$(MAKE) -C $(KDIR) M=$(CURDIR) ARCH=$(ARCH) LLVM=$(LLVM) clean

.PHONY: all clean
