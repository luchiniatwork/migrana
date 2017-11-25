# Migrana

[![Clojars Project](https://img.shields.io/clojars/v/migrana.svg)](https://clojars.org/migrana)

Migrana is a Datomic migration tool loosely inspired on a mix of
[conformity](https://github.com/rkneufeld/conformity) and
[Rails' Active Record Migrations](http://edgeguides.rubyonrails.org/active_record_migrations.html).

Migrana gives you the control over how your Datomic database evolves. It allows you to either write
migrations and ensure that they run once and only once or let Migrana infer schema evolution to you.

## Motivation

Datomic and its immutable nature simplifies migrations tremendously. However, every now and again,
a few things need to be fixed along the way. Two common scenarios are:

1. When schema changes are not trivial and you end up needing to deal with alterations that are not
   directly possible without a few interventions beforehand (see
   [Altering Schema Attributes](http://docs.datomic.com/schema.html#altering-schema-attributes) for
   more details.)
2. When there are data transformation needs as part of the migration (say that now all entities that
   used to have `:card/ratings` now also need a default `:card/has-been-rated?` attributed).

## Table of Contents

* [Getting Started](#getting-started)
* [Usage](#usage)
* [Schema Inference](#schema-inference)
* [Manual Migrations](#manual-migrations)
* [Migrations as Code](#migrations-as-code)
* [Bugs](#bugs)
* [Help!](#help)

## Getting Started

TBD

## Usage

Let's assume you have a Datomic schema file on `resources/schema.edn`:

```clojure
[{:db/ident :person/name
  :db/valueType :db.type/string
  :db/cardinality :db.cardinality/one}
 {:db/ident :person/relationship-status
  :db/valueType :db.type/ref
  :db/cardinality :db.cardinality/one
  :db/doc "Whether the person is married or single."}
 {:db/ident :relationship-status/single}
 {:db/ident :relationship-status/married}
 {:db/ident :relationship-status/divorced}
 {:db/ident :relationship-status/widowed}]]
```

Then run:

```
$ lein migrana datomic:dev://localhost:4334/my-db
```

_Note_: If you have the environment variable `DATOMIC_DB` set, you can call `lein migrana` and it will connect
to the DB specified there.

Migrana will make sure that your schema is in the DB and will also create a migration for
you at `resources/migrations` with the timestamp `YYYYMMDDHHMMSS_schema_inference.edn`. I.e.:

```
$ ls resources/migrations
20171124200143_schema_inference.edn
```

If you run `lein migrana` again, Migrana will not do anything because it will detect that the schema file
is unchanged and that it has already been asserted into the DB.

If you check the contents of `20171124200143_schema_inference.edn` you'll see:

```clojure
{:tx-data [{:db/ident :person/name
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one}
           {:db/ident :person/relationship-status
            :db/valueType :db.type/ref
            :db/cardinality :db.cardinality/one
            :db/doc "Whether the person is married or single."}
           {:db/ident :relationship-status/single}
           {:db/ident :relationship-status/married}
           {:db/ident :relationship-status/divorced}
           {:db/ident :relationship-status/widowed}]}
```

Now, let's suppose we need to add a new attribute for `:person/email` which we wish to be an identity and let's
also add an index to `:person/name`. Let's change our `resources/schema.edn` to:

```clojure
[{:db/ident :person/name
  :db/valueType :db.type/string
  :db/index true
  :db/cardinality :db.cardinality/one}
 {:db/ident :person/email
  :db/valueType :db.type/string
  :db/unique :db.type/identity
  :db/cardinality :db.cardinality/one}
 {:db/ident :person/relationship-status
  :db/valueType :db.type/ref
  :db/cardinality :db.cardinality/one
  :db/doc "Whether the person is married or single."}
 {:db/ident :relationship-status/single}
 {:db/ident :relationship-status/married}
 {:db/ident :relationship-status/divorced}
 {:db/ident :relationship-status/widowed}]]
```

After the change we run `lein migrana` again:

```
$ lein migrana datomic:dev://localhost:4334/my-db
```

And let's check what Migrana has done to our `resources/migrations`:

```
$ ls resources/migrations
20171124200143_schema_inference.edn 20171124200525_schema_inference.edn
```

When you check the content of the new file (`20171124200525_schema_inference.edn`),
this is what you have:

```clojure
{:tx-data [{:db/ident :person/name
            :db/index true}
           {:db/ident :person/email
            :db/valueType :db.type/string
            :db/unique :db.type/identity
            :db/cardinality :db.cardinality/one}]}
```

## Schema Inference

You probably noticed that, until now, the migration files are named `YYYYMMDDHHMMSS_schema_inference.edn`.
That's because running `lein migrana` as we have been running will use its schema inference features (migrana
will infer the migration required based on how the Datomic schema has changed).

Migrana can detect only additions (such as we have seen before). Anything else more advanced will require
a manual migration.

## Manual Migrations

In some cases, the schema inference done by Migrana is not enough. In such cases you can create a manual
migration with:

```
$ lein migrana create retract_name
```

This will create the migration `YYYYMMDDHHMMSS_retract_name.edn` in the `resources/migrations` path:

```
$ ls resources/migrations
20171124200143_schema_inference.edn 20171124200525_schema_inference.edn 20171124200733_retract_name.edn`
```

The file will be empty and you can write your own migration steps in as a vector of the `:tx-data` map entry.

After you edit the file, you can run `lein migrana` as usual and your migration will be sent to the DB.

## Migrations as Code

In some cases you might want to have code that interacts with your manual migratation. In these cases,
simply create an empty manual migration with:

```
$ lein migrana create code_as_migration
```

Then open the migration created in `resources/migrations` and edit to look like something like this:

```clojure
{:tx-fn 'my-project.migrations/backport-bar-attr-to-entities-with-foo}
```

Then in your `src/my_project/migrations.clj` you could have:

```clojure
(ns my-project.migrations
  (:require [datomic.api :as d])

(def find-eids-with-attr
  '[:find [?e ...]
    :in $ ?attr
    :where
    [?e ?attr]])

(defn backport-bar-attr-to-entities-with-foo
  "Find existing entities bearing the `:some/foo` attribute,
   and apply to them the `:some/bar` attribute and value."
  [conn]
  (let [foo-eids (d/q find-eids-with-attr (d/db conn) :some/foo)
        tx-data  (for [eid foo-eids]
                   {:db/id eid
                    :some/bar :bar-value})]
    tx-data))
```

After you edit the file, you can run `lein migrana` as usual and your migration will be sent to the DB.

## Bugs

If you find a bug, submit a [Github issue](https://github.com/luchiniatwork/migrana/issues).

## Help

This project is looking for team members who can help this project succeed!
If you are interested in becoming a team member please open an issue.

## License

Copyright © 2017 Tiago Luchini

Distributed under the MIT License.
