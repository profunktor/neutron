# neutron

[![CI Status](https://github.com/profunktor/neutron/workflows/Scala/badge.svg)](https://github.com/profunktor/neutron/actions)
[![MergifyStatus](https://img.shields.io/endpoint.svg?url=https://gh.mergify.io/badges/profunktor/neutron&style=flat)](https://mergify.io)
[![Maven Central](https://img.shields.io/maven-central/v/dev.profunktor/neutron-core_2.13.svg)](https://search.maven.org/search?q=dev.profunktor.neutron)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-brightgreen.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)
<a href="https://typelevel.org/cats/"><img src="https://raw.githubusercontent.com/typelevel/cats/c23130d2c2e4a320ba4cde9a7c7895c6f217d305/docs/src/main/resources/microsite/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly" /></a>

A *pulsar* is a celestial object, thought to be a rapidly rotating neutron star, that emits regular pulses of radio waves and other electromagnetic radiation at rates of up to one thousand pulses per second.

[![pulsar](https://www.jpl.nasa.gov/spaceimages/images/largesize/PIA18845_hires.jpg "An accreting pulsar. Credit NASA/JPL-Caltech")](https://www.jpl.nasa.gov/spaceimages/?search=pulsar&category=#submit)

### Disclaimer

Neutron started out as a fork of the original [Neutron](https://github.com/cr-org/neutron) developed at Chatroulette, which was a project [gvolpe](https://github.com/gvolpe) started when working at that company. The main motivation for the fork was to support Scala 3, as well as adding other more opinionated changes.

Furthermore, we believe in OSS and want to maintain an [Apache Pulsar](https://pulsar.apache.org/) library compatible with the needs of the community, and not only of the needs of a single company.

### Documentation

Check out the [microsite](https://neutron.profunktor.dev/).

### Development

If you have `sbt` installed, you don't have to worry about anything. Simply run `sbt +test` command in the project root to run the tests.

If you are a `nix` user, make sure you enter a development shell by running `nix develop` on the project's root.

```
sbt +test
```

Remember to first start Pulsar and its configuration via the provided shell script.

```
./run.sh
```

### Schemas

Working with schemas when using our Pulsar `docker-compose` configuration.

Get [schema compatibility strategy](https://pulsar.apache.org/docs/en/schema-evolution-compatibility/#schema-compatibility-check-strategy):

```
$ docker-compose exec pulsar bin/pulsar-admin namespaces get-schema-compatibility-strategy public/default
FULL
```

Set schema compatibility strategy:

```
$ docker-compose exec pulsar bin/pulsar-admin namespaces set-schema-compatibility-strategy -c BACKWARD public/default
```
