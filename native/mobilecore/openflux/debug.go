package openflux

import (
	"fmt"
	"log"
	"os"
	"sync"
)

var (
	debugLog  *log.Logger
	verbose   bool
	logSinkMu sync.RWMutex
	logSink   func(string)
)

func EnableDebug() {
	verbose = true
	debugLog = log.New(os.Stderr, "", log.LstdFlags|log.Lmicroseconds)
	log.SetFlags(log.LstdFlags | log.Lmicroseconds | log.Lshortfile)
}

func Debugf(format string, args ...interface{}) {
	if verbose {
		message := fmt.Sprintf(format, args...)
		debugLog.Output(2, message)

		logSinkMu.RLock()
		sink := logSink
		logSinkMu.RUnlock()
		if sink != nil {
			sink(message)
		}
	}
}

func SetLogSink(sink func(string)) {
	logSinkMu.Lock()
	logSink = sink
	logSinkMu.Unlock()
}

func IsVerbose() bool {
	return verbose
}
