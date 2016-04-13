package model

import "fmt"

// Volume describes how a local path is mounted into a container.
type Volume struct {
	HostPath      string `json:"host_path"`
	ContainerPath string `json:"container_path"`
	ReadOnly      bool   `json:"read_only"`
	Mode          string `json:"mode"`
}

// Device describes the mapping between a host device and the container device.
type Device struct {
	HostPath          string `json:"host_path"`
	ContainerPath     string `json:"container_path"`
	CgroupPermissions string `json:"cgroup_permissions"`
}

// VolumesFrom describes a container that volumes are imported from.
type VolumesFrom struct {
	Tag           string `json:"tag"`
	Name          string `json:"name"`
	NamePrefix    string `json:"name_prefix"`
	URL           string `json:"url"`
	HostPath      string `json:"host_path"`
	ContainerPath string `json:"container_path"`
	ReadOnly      bool   `json:"read_only"`
}

// ContainerImage describes a docker container image.
type ContainerImage struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Tag  string `json:"tag"`
	URL  string `json:"url"`
}

// Container describes a container used as part of a DE job.
type Container struct {
	ID          string         `json:"id"`
	Volumes     []Volume       `json:"container_volumes"`
	Devices     []Device       `json:"container_devices"`
	VolumesFrom []VolumesFrom  `json:"container_volumes_from"`
	Name        string         `json:"name"`
	NetworkMode string         `json:"network_mode"`
	CPUShares   string         `json:"cpu_shares"`
	MemoryLimit string         `json:"memory_limit"`
	Image       ContainerImage `json:"image"`
	EntryPoint  string         `json:"entrypoint"`
	WorkingDir  string         `json:"working_directory"`
}

// HasVolumes returns true if the container has volumes associated with it.
func (c *Container) HasVolumes() bool {
	return len(c.Volumes) > 0
}

// HasDevices returns true if the container has devices associated with it.
func (c *Container) HasDevices() bool {
	return len(c.Devices) > 0
}

// HasVolumesFrom returns true if the container has volumes from associated with
// it.
func (c *Container) HasVolumesFrom() bool {
	return len(c.VolumesFrom) > 0
}

// WorkingDirectory returns the container's working directory. Defaults to
// /de-app-work if the job submission didn't specify one. Use this function
// rather than accessing the field directly.
func (c *Container) WorkingDirectory() string {
	if c.WorkingDir == "" {
		return "/de-app-work"
	}
	return c.WorkingDir
}

// CPUSharesOption returns a string containing the docker command-line option
// that sets the number of cpu shares the container is allotted.
func (c *Container) CPUSharesOption() string {
	if c.CPUShares != "" {
		return fmt.Sprintf("--cpu-shares=%s", c.CPUShares)
	}
	return ""
}

// MemoryLimitOption returns a string containing the docker command-line option
// that sets the maximum amount of host memory that the container may use.
func (c *Container) MemoryLimitOption() string {
	if c.MemoryLimit != "" {
		return fmt.Sprintf("--memory=%s", c.MemoryLimit)
	}
	return ""
}

// IsDEImage returns true if container image is one of the DE image that requires
// special tag logic.
func (c *Container) IsDEImage() bool {
	deImages := []string{
		"discoenv/porklock",
		"discoenv/curl-wrapper",
		"gims.iplantcollaborative.org:5000/backwards-compat",
		"discoenv/backwards-compat",
	}
	actualName := c.Image.Name
	found := false
	for _, d := range deImages {
		if actualName == d {
			found = true
		}
	}
	return found
}
