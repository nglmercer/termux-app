CC ?= cc
CFLAGS ?= -O2 -std=c11 -Wall -Wextra -Werror
PREFIX ?= /data/data/com.termux/files/usr

termux-control: termux-control.c
	$(CC) $(CFLAGS) $< -o $@

install: termux-control
	install -m 0755 termux-control $(DESTDIR)$(PREFIX)/bin/termux-control

.PHONY: install
