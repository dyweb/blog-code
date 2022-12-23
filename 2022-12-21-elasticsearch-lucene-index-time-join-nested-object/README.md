# How Elasticsearch uses Lucene index time join to handle nested objects

Sample code for blog TODO: link to blog https://github.com/dyweb/blog/pull/59

## Usage

- Run the unit tests directly in IDE.
- Java 11 required
  by [Lucene 9](https://cwiki.apache.org/confluence/display/LUCENE/LuceneFAQ#LuceneFAQ-WhatJavaversionisrequiredtorunLucene?)

## What's included

List of keywords so it might show up in search results ...

- Import lucene using gradle
- Index and search document
- Flatten nested object, which does not work as expected...
- Handle array of object in a document/object