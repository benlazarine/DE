package main

import (
	"configurate"
	"testing"
)

func inittests(t *testing.T) {
	configurate.Init("../test/test_config.yaml")
	configurate.C.Set("irods.base", "/path/to/irodsbase")
	configurate.C.Set("irods.host", "hostname")
	configurate.C.Set("irods.port", "1247")
	configurate.C.Set("irods.user", "user")
	configurate.C.Set("irods.pass", "pass")
	configurate.C.Set("irods.zone", "test")
	configurate.C.Set("irods.resc", "")
	configurate.C.Set("condor.log_path", "/path/to/logs")
	configurate.C.Set("condor.porklock_tag", "test")
	configurate.C.Set("condor.filter_files", "foo,bar,baz,blippy")
	configurate.C.Set("condor.request_disk", "0")
}

func TestJobFiles(t *testing.T) {
	listing, err := jobFiles("../test/")
	if err != nil {
		t.Error(err)
	}
	expectedLength := 3
	actualLength := len(listing)
	if actualLength != expectedLength {
		t.Errorf("length of listing was %d instead of %d", actualLength, expectedLength)
	}
	found0 := false
	found1 := false
	found2 := false
	for _, filepath := range listing {
		if filepath == "../test/00000000-0000-0000-0000-000000000000.json" {
			found0 = true
		}
		if filepath == "../test/00000000-0000-0000-0000-000000000001.json" {
			found1 = true
		}
		if filepath == "../test/00000000-0000-0000-0000-000000000002.json" {
			found2 = true
		}
	}
	if !found0 {
		t.Error("Path ../test/00000000-0000-0000-0000-000000000000.json was not found")
	}
	if !found1 {
		t.Error("Path ../test/00000000-0000-0000-0000-000000000001.json was not found")
	}
	if !found2 {
		t.Error("Path ../test/00000000-0000-0000-0000-000000000002.json was not found")
	}
}

func TestJobs(t *testing.T) {
	inittests(t)
	paths, err := jobFiles("../test/")
	if err != nil {
		t.Error(err)
	}
	listing, err := jobs(paths)
	if err != nil {
		t.Error(err)
	}
	actualLength := len(listing)
	expectedLength := 3
	if actualLength != expectedLength {
		t.Errorf("length of listing was %d instead of %d", actualLength, expectedLength)
	}
	found0 := false
	found1 := false
	found2 := false
	for _, j := range listing {
		switch j.InvocationID {
		case "07b04ce2-7757-4b21-9e15-0b4c2f44be26":
			found0 = true
		case "07b04ce2-7757-4b21-9e15-0b4c2f44be27":
			found1 = true
		case "07b04ce2-7757-4b21-9e15-0b4c2f44be28":
			found2 = true
		}
	}
	if !found0 {
		t.Error("InvocationID 07b04ce2-7757-4b21-9e15-0b4c2f44be26 was not found")
	}
	if !found1 {
		t.Error("InvocationID 07b04ce2-7757-4b21-9e15-0b4c2f44be27 was not found")
	}
	if !found2 {
		t.Error("InvocationID 07b04ce2-7757-4b21-9e15-0b4c2f44be28 was not found")
	}
}

func TestJobImages(t *testing.T) {
	inittests(t)
	paths, err := jobFiles("../test/")
	if err != nil {
		t.Error(err)
	}
	listing, err := jobs(paths)
	if err != nil {
		t.Error(err)
	}
	images := jobImages(listing)
	actualLength := len(images)
	expectedLength := 2
	if actualLength != expectedLength {
		t.Errorf("Number of images was %d instead of %d", actualLength, expectedLength)
	}
	found0 := false
	found1 := false
	for _, i := range images {
		switch i {
		case "gims.iplantcollaborative.org:5000/backwards-compat:latest":
			found0 = true
		case "gims.iplantcollaborative.org:5000/fake-image:latest":
			found1 = true
		}
	}
	if !found0 {
		t.Error("Did not find the backwards-compat image")
	}
	if !found1 {
		t.Error("Did not find the fake-image image")
	}
}
