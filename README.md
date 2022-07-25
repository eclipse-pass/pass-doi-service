# pass-doi-service

Service for accepting a DOI and returning a Journal ID and Crossref metadata for the DOI

## Description

This service accepts a journal DOI as a query parameter:

`http://<host>:<port>/journal?doi=<doi>`

DOIs must contain a form like `10.1234/ ...`
If a DOI is of a longer URL form containing the string `doi.org/`, then we truncate the DOI to take everything after
this substring.

The service validates the form of the doi - if it is valid, then we hit the Crossref API to get information about the
corresponding journal. We then check to see if there is a
`Journal` object in PASS for this journal. If not we create one. The service then returns to the caller a JSON object
containing the `journal-id` of the PASS journal, and a `crossref` object representing the data returned to the service
as a result of the Crossref call.

## Configuration

The service will look for an environment variable called PASS_DOI_SERVICE_MAILTO to specify a value on the User-Agent
header on the Crossref request. If not present, the default is
`pass@jhu.edu`. The service will require the following environment variables for the java client for Fedora if the
defaults are not to be used:

```
PASS_FEDORA_USER
PASS_FEDORA_PASSWORD
PASS_FEDORA_BASEURL
PASS_ELASTICSEARCH_URL
PASS_ELASTICSEARCH_LIMIT
```

In addition these environment variables need to be present to translate internal Journal ids to the external form
presented to clients:

```
PASS_FEDORA_BASEURL
PASS_EXTERNAL_FEDORA_BASEURL
```

## Release

This project will build it's own production ready Docker image locally, but will not automatically push the image. To build, run:

``` sh
mvn install
```

This will run all tests and build the final Docker image with the tag: `oapass/doi-service:${project.version}`. You can optionally retag the image with a custom tag:

``` sh
docker tag <originalTag> <newTag>
# E.g. Building this project at version 0.2.0-SNAPSHOT, but create a tag for 0.1.0 version
docker tag oapass/doi-service:0.2.0-SNAPSHOT oapass/doi-service:0.1.0
```

When ready, you can manually push the Docker image to Docker Hub:

``` sh
docker push <image>:<tag>
# E.g. Push the 0.1.0 version of the image
docker push oapass/doi-service:0.1.0
```
