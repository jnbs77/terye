# Class: mongodb
#
# This class installs MongoDB (stable)
#
# Notes:
#  This class is Ubuntu specific.
#  By Sean Porter, Gastown Labs Inc.
#
# Actions:
#  - Install MongoDB using a 10gen Ubuntu repository
#  - Manage the MongoDB service
#  - MongoDB can be part of a replica set
#
# Sample Usage:
#  include mongodb
#
class mongodb {
#	include mongodb::params
	
	package { "python-software-properties":
		ensure => installed,
		require => Exec["update-apt-1"],
	}
	
	exec { "update-apt-1":
		path => "/bin:/usr/bin",
		command => "apt-get update",
	}

	exec { "10gen-apt-repo":
		path => "/bin:/usr/bin",
#		command => "add-apt-repository '${mongodb::params::repository}'",
		command => "add-apt-repository 'deb http://downloads.mongodb.org/distros/ubuntu 10.4 10gen'",
		unless => "cat /etc/apt/sources.list | grep 10gen",
		require => Package["python-software-properties"],
	}
	
	exec { "10gen-apt-key":
		path => "/bin:/usr/bin",
		command => "apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 7F0CEB10",
		unless => "apt-key list | grep 10gen",
		require => Exec["10gen-apt-repo"],
	}
	
	exec { "update-apt":
		path => "/bin:/usr/bin",
		command => "apt-get update",
		unless => "ls /usr/bin | grep mongo",
		require => Exec["10gen-apt-key"],
	}

	package { "mongodb-stable":
		ensure => installed,
		require => Exec["update-apt"],
	}
	
	service { "mongodb":
		enable => true,
		ensure => running,
		require => Package["mongodb-stable"],
	}
	
	define replica_set {
		file { "/etc/init/mongodb.conf":
			content => template("mongodb/mongodb.conf.erb"),
			mode => "0644",
			notify => Service["mongodb"],
			require => Package["mongodb-stable"],
		}
	}
}
