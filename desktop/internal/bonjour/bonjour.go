package bonjour

import (
	"fmt"
	"os"
	"strings"

	"github.com/grandcat/zeroconf"
)

const ServiceType = "_droidtrans._tcp"

func Advertise(instance string, port int) (func(), error) {
	instance = strings.TrimSpace(instance)
	if instance == "" {
		instance, _ = os.Hostname()
	}
	if instance == "" {
		instance = "DroidTrans"
	}
	server, err := zeroconf.Register(instance, ServiceType, "local.", port, []string{
		"txtvers=1",
		"app=droidtrans",
	}, nil)
	if err != nil {
		return func() {}, err
	}
	fmt.Println("bonjour ", ServiceType, instance)
	return server.Shutdown, nil
}
